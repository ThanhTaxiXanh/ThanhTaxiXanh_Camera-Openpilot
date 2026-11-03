package vn.thanhtaxixanh.camera.ai

/**
 * Selects the next transport after failed RTSP playback.
 *
 * In the observed 70mai A500S case, SETUP/PLAY succeed over UDP but no
 * RTP packets or complete H264 access units arrive. Retrying the identical
 * UDP transport is pointless; attempt a fresh RTSP session with TCP.
 */
object CameraRtspFailoverPolicy {
    fun shouldTryTcp(
        userRequestedUdp: Boolean,
        negotiatedTransport: String?,
        reason: String
    ): Boolean {
        if (!userRequestedUdp || negotiatedTransport != "RTP/UDP") return false
        return reason.contains("zero video packets", ignoreCase = true) ||
            reason.contains("zero H264 access units", ignoreCase = true)
    }
}
