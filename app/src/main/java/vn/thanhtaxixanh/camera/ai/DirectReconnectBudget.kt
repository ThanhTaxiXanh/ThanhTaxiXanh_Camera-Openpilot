package vn.thanhtaxixanh.camera.ai

/**
 * Sliding-window, bounded reconnect budget.
 *
 * The previous MainActivity implementation reset the retry count on *every*
 * failure after the initial stream had been open for 60 seconds, allowing
 * an unbounded reconnection storm in a long-running session. This controller
 * expires only individual OLD failures, never clears recent failures because
 * overall session uptime is high. No background scheduling is performed here.
 */
class DirectReconnectBudget(
    private val maxAttempts: Int = 3,
    private val rollingWindowMs: Long = 60_000L
) {
    init {
        require(maxAttempts in 1..10)
        require(rollingWindowMs in 5_000L..3_600_000L)
    }
    enum class Status { RETRY, LIMIT_REACHED, INVALID_CLOCK }
    data class Decision(
        val status: Status,
        val attemptsWithinWindow: Int,
        val delayMs: Long,
        val note: String
    ) {
        val permitted: Boolean get() = status == Status.RETRY
    }

    private val failures = ArrayDeque<Long>()
    private var previousAt = -1L

    @Synchronized fun onInterrupted(nowMs: Long): Decision {
        if (nowMs < 0L || (previousAt >= 0L && nowMs < previousAt))
            return Decision(Status.INVALID_CLOCK, failures.size,0L,
                "Invalid monotonic reconnect clock")
        previousAt = nowMs
        while (failures.isNotEmpty() && nowMs-failures.first() >= rollingWindowMs)
            failures.removeFirst()
        if (failures.size >= maxAttempts)
            return Decision(Status.LIMIT_REACHED, failures.size,0L,
                "Too many RTSP/codec restarts inside the rolling window")
        failures.addLast(nowMs)
        val attempt = failures.size
        return Decision(Status.RETRY,attempt,
            (1200L*attempt).coerceAtMost(6000L),
            "Bounded retry after release of previous codec")
    }

    @Synchronized fun reset() {
        failures.clear()
        previousAt = -1L
    }

    @Synchronized fun outstandingAttempts(): Int = failures.size
}
