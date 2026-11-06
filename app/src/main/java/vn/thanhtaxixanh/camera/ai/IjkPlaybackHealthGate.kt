package vn.thanhtaxixanh.camera.ai

/**
 * Native IJKPlayer health sampling. INFO_VIDEO_RENDERING_START only means one
 * frame appeared; it must never suppress a later sustained-FPS timeout.
 *
 * IJK's output FPS is a player-side rate estimate, NOT an independent frame
 * counter or proof of optical motion. This watchdog is only a recovery guard.
 */
class IjkPlaybackHealthGate(
    private val startupTimeoutMs: Long = 12_000L,
    private val stallTimeoutMs: Long = 5_500L,
    private val staleSamplesBeforeRecovery: Int = 2,
    private val minHealthyOutputFps: Float = 2f
) {
    init {
        require(startupTimeoutMs > 0)
        require(stallTimeoutMs > 0)
        require(staleSamplesBeforeRecovery >= 1)
        require(minHealthyOutputFps.isFinite() && minHealthyOutputFps > 0f)
    }

    enum class Verdict { WAITING, HEALTHY, START_TIMEOUT, STALLED }

    private var openedAtMs = -1L
    private var lastSampleMs = -1L
    private var lastHealthyMs = -1L
    private var overdueSamples = 0

    @Synchronized fun reset(atElapsedMs: Long) {
        require(atElapsedMs >= 0L)
        openedAtMs = atElapsedMs
        lastSampleMs = atElapsedMs
        lastHealthyMs = -1L
        overdueSamples = 0
    }

    @Synchronized fun sample(
        atElapsedMs: Long,
        outputFps: Float,
        nativePlaying: Boolean
    ): Verdict {
        if (openedAtMs < 0L || atElapsedMs <= lastSampleMs) return Verdict.WAITING
        lastSampleMs = atElapsedMs

        // A previously sampled positive FPS is not proof of ongoing playback.
        // When isPlaying=false, never refresh the healthy window.
        if (nativePlaying && outputFps.isFinite() && outputFps > minHealthyOutputFps) {
            lastHealthyMs = atElapsedMs
            overdueSamples = 0
            return Verdict.HEALTHY
        }
        if (lastHealthyMs < 0L) {
            return if (atElapsedMs - openedAtMs > startupTimeoutMs)
                Verdict.START_TIMEOUT else Verdict.WAITING
        }
        if (atElapsedMs - lastHealthyMs > stallTimeoutMs) {
            overdueSamples++
            if (overdueSamples >= staleSamplesBeforeRecovery) return Verdict.STALLED
        } else {
            overdueSamples = 0
        }
        return Verdict.WAITING
    }
}
