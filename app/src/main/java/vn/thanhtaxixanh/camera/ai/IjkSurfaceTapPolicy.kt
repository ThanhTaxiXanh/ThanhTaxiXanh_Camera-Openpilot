package vn.thanhtaxixanh.camera.ai

/**
 * Fail-closed admission gate for an opt-in PixelCopy of IJK's already
 * rendered Surface. No additional H264 decoder or native YUV tap exists.
 * Timestamps are local request times, NOT A500S exposure timestamps.
 */
class IjkSurfaceTapPolicy(
    private val minimumIntervalMs: Long = 350L,
    private val maxRequestAgeMs: Long = 550L
) {
    init {
        require(minimumIntervalMs in 100L..2_000L)
        require(maxRequestAgeMs in minimumIntervalMs..2_000L)
    }

    data class Ticket(val epoch: Long, val sequence: Long, val requestedAtMs: Long)
    data class Metrics(
        val attempts: Long,
        val completed: Long,
        val rejected: Long,
        val errors: Long,
        val stale: Long,
        val busySkips: Long,
        val active: Boolean
    )
    private var active = false
    private var epoch = 0L
    private var sequence = 0L
    private var lastRequestAtMs = -1L
    private var inFlight: Ticket? = null
    private var attempts = 0L
    private var completed = 0L
    private var rejected = 0L
    private var errors = 0L
    private var stale = 0L
    private var busySkips = 0L

    @Synchronized fun start() {
        epoch++
        active = true
        inFlight = null
        lastRequestAtMs = -1L
    }

    @Synchronized fun stop() {
        epoch++
        active = false
        inFlight = null
    }

    @Synchronized fun request(
        atMs: Long, playing: Boolean, outputFps: Float,
        videoWidth: Int, videoHeight: Int, surfaceValid: Boolean
    ): Ticket? {
        if (!active || atMs < 0L || !surfaceValid ||
            videoWidth != 640 || videoHeight != 480 ||
            !playing || !outputFps.isFinite() || outputFps <= 2f) {
            rejected++
            return null
        }
        if (inFlight != null) {
            busySkips++
            return null
        }
        if (lastRequestAtMs >= 0L &&
            atMs - lastRequestAtMs < minimumIntervalMs) return null
        if (lastRequestAtMs > atMs) {
            rejected++
            return null
        }
        val ticket = Ticket(epoch, ++sequence, atMs)
        lastRequestAtMs = atMs
        inFlight = ticket
        attempts++
        return ticket
    }

    /** Never accept an old session's callback or a slow pixel readback. */
    @Synchronized fun complete(ticket: Ticket, callbackAtMs: Long, success: Boolean): Boolean {
        if (!active || ticket.epoch != epoch || inFlight != ticket) {
            rejected++
            return false
        }
        inFlight = null
        if (!success) {
            errors++
            return false
        }
        if (callbackAtMs < ticket.requestedAtMs ||
            callbackAtMs - ticket.requestedAtMs > maxRequestAgeMs) {
            stale++
            return false
        }
        completed++
        return true
    }

    @Synchronized fun isCurrent(ticket: Ticket): Boolean =
        active && ticket.epoch == epoch

    @Synchronized fun metrics(): Metrics =
        Metrics(attempts, completed, rejected, errors, stale, busySkips, active)
}
