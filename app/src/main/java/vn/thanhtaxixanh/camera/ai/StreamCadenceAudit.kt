package vn.thanhtaxixanh.camera.ai

/**
 * Bounded, thread-safe diagnostic of *LOCAL* AU -> decoder -> Surface flow.
 *
 * The same monotonic Android elapsedRealtime clock is required for every
 * event. PTS must be from the same, session-local RTP 90 kHz unwrap domain.
 *
 * Counters are observational. A deficit at the render-callback stage can
 * mean vendor callbacks are absent, NOT that the display is stalled.
 * RTP timestamps do not identify camera optical exposure time.
 * This module has no dependency on MediaCodec, Android or actual 70mai data.
 */
class StreamCadenceAudit(
    private val windowMs: Long = 10_000L,
    private val maxEventsPerStage: Int = 2048
) {
    init {
        require(windowMs in 4_000L..60_000L)
        require(maxEventsPerStage in 128..4096)
    }

    enum class Stage {
        AU_RECEIVED, CODEC_INPUT, CODEC_OUTPUT, SURFACE_RELEASE, RENDER_CALLBACK
    }

    enum class Status {
        WARMUP,
        SOURCE_AU_STALLED,
        CODEC_INPUT_LAGGING,
        CODEC_OUTPUT_LAGGING,
        CODEC_OUTPUT_MEDIA_BACKLOG,
        SURFACE_RELEASE_LAGGING,
        SURFACE_RELEASE_MEDIA_BACKLOG,
        RENDER_CALLBACK_UNCONFIRMED,
        FLOW_OBSERVED
    }

    data class Snapshot(
        val status: Status,
        val elapsedMs: Long,
        val sourceFps: Double?,
        val codecInputFps: Double?,
        val codecOutputFps: Double?,
        val surfaceReleaseFps: Double?,
        val matchedRenderCallbackFps: Double?,
        val mediaClockFps: Double?,
        val mediaGapToCodecOutputMs: Double?,
        val mediaGapToSurfaceReleaseMs: Double?,
        val sourceStallMs: Long?,
        val invalidEvents: Long,
        val boundedEvictions: Long,
        val note: String = "Local AU/codec/Surface cadence only, NOT camera-to-glass latency"
    )

    private val events = Array(Stage.entries.size) { ArrayDeque<Long>() }
    private val lastClock = LongArray(Stage.entries.size) { -1L }
    private val highestPts = LongArray(Stage.entries.size) { -1L }
    private val ptsInSourceWindow = ArrayDeque<Pair<Long,Long>>()
    private var firstSourceAtMs = -1L
    private var invalid = 0L
    private var evictions = 0L

    /** Never performs codec/network work or waits on any other thread. */
    @Synchronized fun observe(
        stage: Stage, ptsUs: Long, atElapsedMs: Long
    ): Boolean {
        val k = stage.ordinal
        if (ptsUs < 0L || atElapsedMs < 0L ||
            (firstSourceAtMs >= 0 && atElapsedMs < firstSourceAtMs) ||
            (lastClock[k] >= 0 && atElapsedMs < lastClock[k]) ||
            (firstSourceAtMs < 0 && stage != Stage.AU_RECEIVED)) {
            invalid++
            return false
        }
        if (firstSourceAtMs < 0L) firstSourceAtMs = atElapsedMs
        lastClock[k] = atElapsedMs
        if (ptsUs > highestPts[k]) highestPts[k] = ptsUs
        val row = events[k]
        row.addLast(atElapsedMs)
        trim(row, atElapsedMs)
        if (stage == Stage.AU_RECEIVED) {
            ptsInSourceWindow.addLast(atElapsedMs to ptsUs)
            while (ptsInSourceWindow.isNotEmpty() &&
                (atElapsedMs-ptsInSourceWindow.first().first > windowMs ||
                    ptsInSourceWindow.size > maxEventsPerStage)) {
                ptsInSourceWindow.removeFirst()
            }
        }
        return true
    }

    private fun trim(row: ArrayDeque<Long>, nowMs: Long) {
        while (row.isNotEmpty() &&
            (nowMs-row.first() > windowMs ||
                row.size > maxEventsPerStage)) {
            if (row.size > maxEventsPerStage) evictions++
            row.removeFirst()
        }
    }

    @Synchronized fun snapshot(nowElapsedMs: Long): Snapshot {
        // Invalid timebase must never fabricate rates or progress.
        if (nowElapsedMs < 0 ||
            (firstSourceAtMs >= 0 && nowElapsedMs < firstSourceAtMs)) {
            return Snapshot(Status.WARMUP,0,null,null,null,null,null,
                null,null,null,null,invalid+1,evictions)
        }
        val elapsed = if (firstSourceAtMs < 0L) 0L
            else nowElapsedMs-firstSourceAtMs
        val rates = DoubleArray(Stage.entries.size)
        for(stage in Stage.entries) {
            val row=events[stage.ordinal]
            trim(row,nowElapsedMs)
            rates[stage.ordinal] = row.size * 1000.0 / windowMs
        }
        while (ptsInSourceWindow.isNotEmpty() &&
            nowElapsedMs-ptsInSourceWindow.first().first > windowMs)
            ptsInSourceWindow.removeFirst()
        val mature = elapsed >= windowMs
        val inputStall = if (firstSourceAtMs < 0L) null
            else (nowElapsedMs-lastClock[Stage.AU_RECEIVED.ordinal])
                .coerceAtLeast(0L)
        val sourceClockFps = if (mature && ptsInSourceWindow.size >= 2) {
            val minPts=ptsInSourceWindow.minOf { it.second }
            val maxPts=ptsInSourceWindow.maxOf { it.second }
            val range=maxPts-minPts
            if (range > 0L) (ptsInSourceWindow.size-1)*1_000_000.0/range
            else null
        } else null
        fun mediaGap(from: Stage, to: Stage): Double? {
            val leading=highestPts[from.ordinal]
            val trailing=highestPts[to.ordinal]
            if (leading < 0L || trailing < 0L || trailing > leading)
                return null
            return (leading-trailing)/1000.0
        }
        val codecMediaGap = mediaGap(Stage.AU_RECEIVED,Stage.CODEC_OUTPUT)
        val surfaceMediaGap = mediaGap(Stage.AU_RECEIVED,Stage.SURFACE_RELEASE)
        val result = if (!mature) Status.WARMUP
        else if ((inputStall ?: 0L) > 2_500L)
            Status.SOURCE_AU_STALLED
        else if (events[Stage.AU_RECEIVED.ordinal].size < 30)
            Status.WARMUP
        else if (rates[Stage.CODEC_INPUT.ordinal] <
            rates[Stage.AU_RECEIVED.ordinal]*.80)
            Status.CODEC_INPUT_LAGGING
        else if (rates[Stage.CODEC_OUTPUT.ordinal] <
            rates[Stage.CODEC_INPUT.ordinal]*.80)
            Status.CODEC_OUTPUT_LAGGING
        else if (codecMediaGap != null && codecMediaGap > 1_000.0)
            Status.CODEC_OUTPUT_MEDIA_BACKLOG
        else if (rates[Stage.SURFACE_RELEASE.ordinal] <
            rates[Stage.CODEC_OUTPUT.ordinal]*.80)
            Status.SURFACE_RELEASE_LAGGING
        else if (surfaceMediaGap != null && surfaceMediaGap > 1_000.0)
            Status.SURFACE_RELEASE_MEDIA_BACKLOG
        else if (rates[Stage.SURFACE_RELEASE.ordinal] >= 4.0 &&
            rates[Stage.RENDER_CALLBACK.ordinal] <
                rates[Stage.SURFACE_RELEASE.ordinal]*.60)
            Status.RENDER_CALLBACK_UNCONFIRMED
        else Status.FLOW_OBSERVED

        fun rate(stage: Stage) = if (mature) rates[stage.ordinal] else null
        return Snapshot(result,elapsed,rate(Stage.AU_RECEIVED),
            rate(Stage.CODEC_INPUT),rate(Stage.CODEC_OUTPUT),
            rate(Stage.SURFACE_RELEASE),rate(Stage.RENDER_CALLBACK),
            sourceClockFps,
            codecMediaGap,
            surfaceMediaGap,
            inputStall,invalid,evictions)
    }
}
