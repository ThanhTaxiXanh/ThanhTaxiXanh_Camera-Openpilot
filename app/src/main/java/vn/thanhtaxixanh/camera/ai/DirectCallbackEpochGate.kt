package vn.thanhtaxixanh.camera.ai

import java.util.concurrent.atomic.AtomicLong

/**
 * A Direct RTSP reconnect reuses Activity generation. A per-player lease
 * rejects late callbacks from the retired offscreen decoder/RTSP session.
 */
class DirectCallbackEpochGate {
    private val active = AtomicLong()
    fun begin(): Long = active.incrementAndGet()
    fun revoke(): Long = active.incrementAndGet()
    fun isCurrent(lease: Long): Boolean = lease > 0L && active.get() == lease
}
