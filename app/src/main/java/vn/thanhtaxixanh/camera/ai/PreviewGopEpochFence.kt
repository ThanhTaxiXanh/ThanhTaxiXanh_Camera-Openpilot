package vn.thanhtaxixanh.camera.ai

/**
 * Surface preview H264 decoder epoch safety. Any RTP damage after a preview
 * access unit was queued requires a NEW MediaCodec/RTSP session. Even if a
 * later IDR is complete, native callback/output buffers from the old GOP
 * might still be in flight. Never flush MediaCodec on an RTP callback thread.
 *
 * This pure state machine is per-player, not a global reconnect policy.
 */
class PreviewGopEpochFence {
    enum class Recovery { AWAIT_FIRST_IDR, RECONNECT_PREVIEW, RECONNECT_PENDING }
    data class Snapshot(
        val queuedAccessUnits: Long,
        val damageEvents: Long,
        val reconnectRequests: Long,
        val reconnectRequired: Boolean
    )

    private var queuedAccessUnits = 0L
    private var damageEvents = 0L
    private var reconnectRequests = 0L
    private var reconnectRequired = false

    @Synchronized fun mayQueue(): Boolean = !reconnectRequired

    /** Call only after the AU is accepted by the preview input queue. */
    @Synchronized fun queued() {
        check(!reconnectRequired) {
            "Cannot admit an AU from a revoked preview H264 epoch"
        }
        queuedAccessUnits++
    }

    @Synchronized fun damaged(): Recovery {
        damageEvents++
        if (reconnectRequired) return Recovery.RECONNECT_PENDING
        if (queuedAccessUnits == 0L) return Recovery.AWAIT_FIRST_IDR
        reconnectRequired = true
        reconnectRequests++
        return Recovery.RECONNECT_PREVIEW
    }

    @Synchronized fun snapshot() = Snapshot(
        queuedAccessUnits, damageEvents, reconnectRequests, reconnectRequired
    )
}
