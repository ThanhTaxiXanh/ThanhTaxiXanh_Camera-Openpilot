package vn.thanhtaxixanh.camera.ai

import java.util.Locale

/**
 * Owner-initiated five-minute LOCAL playback-progress evidence.
 *
 * Direct frame counts represent Surface release, not photons emitted by
 * the display. IJK output FPS is native player telemetry, not optical
 * scanout. Neither can measure A500S exposure-to-glass latency.
 *
 * This recorder is bounded and clock-domain safe; it never stores images,
 * network credentials, model outputs, GPS or raw RTSP URLs.
 */
class FiveMinuteStreamEvidence(
    private val requiredDurationMs: Long = 300_000L,
    private val maxTickGapMs: Long = 2_500L,
    private val maxNoProgressMs: Long = 4_000L,
    private val maxRows: Int = 480
) {
    init {
        require(requiredDurationMs in 5_000L..900_000L)
        require(maxTickGapMs in 500L..10_000L)
        require(maxNoProgressMs in 1_000L..15_000L)
        require(maxRows in 20..2_000)
    }

    enum class Backend { DIRECT, IJK }

    data class Sample(
        val atElapsedMs: Long,
        val backend: Backend,
        /** Only DIRECT's Surface release counter; null for IJK. */
        val releasedFrames: Long? = null,
        /** Only IJK's native output fps; null for DIRECT. */
        val ijkOutputFps: Float? = null,
        val ijkPlaying: Boolean = false,
        val reconnecting: Boolean = false
    )

    private val faults = LinkedHashSet<String>()
    private val rows = StringBuilder(48_000)
    private var backend: Backend? = null
    private var beginMs = -1L
    private var lastTickMs = -1L
    private var lastProgressMs = -1L
    private var lastReleased: Long? = null
    private var lastIJKProgress = false
    private var sampleCount = 0
    private var progressCount = 0
    private var reconnectCount = 0
    private var lastElapsed = 0L
    private var completion: String? = null

    @Synchronized fun start(source: Backend, elapsedMs: Long) {
        require(elapsedMs >= 0L)
        require(beginMs < 0L) { "A trace has already started" }
        backend = source
        beginMs = elapsedMs
        lastTickMs = elapsedMs
        lastProgressMs = elapsedMs
        rows.append("TTXLIVE01\n")
        rows.append("trace_clock=ANDROID_ELAPSED_REALTIME_MS\n")
        rows.append("backend=").append(source.name).append('\n')
        rows.append("sampling_scope=LOCAL_DECODER_AND_PLAYER_TELEMETRY_ONLY\n")
        rows.append("camera_exposure_to_glass_ms=NOT_MEASURED\n")
        rows.append("optical_review=REQUIRED_AND_NOT_INCLUDED\n")
        rows.append("sample:elapsed_ms,source,local_progress,progress_value,reconnecting\n")
    }

    @Synchronized fun isActive(): Boolean = beginMs >= 0L && completion == null
    @Synchronized fun elapsedMs(nowMs: Long): Long =
        if (beginMs < 0L || nowMs < beginMs) 0L else nowMs - beginMs

    private fun problem(code: String) { faults.add(code) }

    @Synchronized fun reconnect(atElapsedMs: Long) {
        if (!isActive()) return
        reconnectCount++
        problem("RECONNECT_OBSERVED")
        if (atElapsedMs < beginMs || atElapsedMs < lastTickMs)
            problem("RECONNECT_INVALID_CLOCK")
        rows.append("event:reconnect,")
            .append((atElapsedMs - beginMs).coerceAtLeast(0L))
            .append('\n')
    }

    /**
     * Return true when target elapsed and the recorder has been sealed.
     * A failure still produces an exportable report but NEVER a field PASS.
     */
    @Synchronized fun sample(input: Sample): Boolean {
        if (!isActive()) return false
        val start = beginMs
        if (input.atElapsedMs < start ||
            input.atElapsedMs <= lastTickMs) {
            problem("INVALID_OR_NONMONOTONIC_CLOCK")
            seal("INVALID_CLOCK", lastTickMs)
            return true
        }
        val delta = input.atElapsedMs - lastTickMs
        if (delta > maxTickGapMs) problem("SAMPLE_GAP_EXCEEDED")
        lastTickMs = input.atElapsedMs
        lastElapsed = input.atElapsedMs - start
        if (input.backend != backend) problem("BACKEND_CHANGED")
        if (input.reconnecting) problem("RECONNECT_OBSERVED")
        var moving = false
        var value = "NA"
        if (input.backend == Backend.DIRECT) {
            val count = input.releasedFrames
            when {
                count == null || count < 0L -> problem("MISSING_SURFACE_RELEASE_COUNT")
                lastReleased != null && count < lastReleased!! -> {
                    problem("SURFACE_COUNTER_RESET")
                    lastReleased = count
                }
                else -> {
                    moving = lastReleased?.let { count > it } ?: count > 0L
                    lastReleased = count
                    value = count.toString()
                }
            }
        } else {
            val fps = input.ijkOutputFps
            if (fps == null || !fps.isFinite() || fps < 0f) {
                problem("INVALID_IJK_FPS")
            } else {
                moving = input.ijkPlaying && fps >= 2f
                value = String.format(Locale.US, "%.2f", fps)
            }
            lastIJKProgress = moving
        }
        if (moving) {
            progressCount++
            lastProgressMs = input.atElapsedMs
        } else if (input.atElapsedMs - lastProgressMs > maxNoProgressMs) {
            problem("VIDEO_PROGRESS_STALLED")
        }

        sampleCount++
        if (sampleCount <= maxRows) {
            rows.append("sample:").append(lastElapsed).append(',')
                .append(input.backend.name).append(',')
                .append(if (moving) "PROGRESS" else "NO_PROGRESS").append(',')
                .append(value).append(',')
                .append(input.reconnecting).append('\n')
        } else {
            problem("SAMPLE_CAP_EXCEEDED")
            seal("SAMPLE_CAP", input.atElapsedMs)
            return true
        }
        if (lastElapsed >= requiredDurationMs) {
            seal("DURATION_REACHED", input.atElapsedMs)
            return true
        }
        return false
    }

    @Synchronized fun stop(nowElapsedMs: Long, reason: String) {
        if (!isActive()) return
        if (reason != "DURATION_REACHED") problem("SESSION_INTERRUPTED")
        if (nowElapsedMs < lastTickMs) problem("STOP_INVALID_CLOCK")
        seal(reason.take(60).replace(',', '_').replace('\n', '_'),
            maxOf(lastTickMs, nowElapsedMs))
    }

    private fun seal(reason: String, atElapsedMs: Long) {
        if (completion != null) return
        val elapsed = if (atElapsedMs >= beginMs) atElapsedMs - beginMs else 0L
        val minSamples = (requiredDurationMs / maxTickGapMs).toInt()
        if (sampleCount < minSamples) problem("INSUFFICIENT_SAMPLES")
        if (progressCount < minSamples / 2) problem("INSUFFICIENT_FRAME_PROGRESS")
        val localPass = reason == "DURATION_REACHED" &&
            elapsed >= requiredDurationMs && faults.isEmpty()
        val note = buildString {
            append("end_reason=").append(reason).append('\n')
            append("elapsed_ms=").append(elapsed).append('\n')
            append("required_ms=").append(requiredDurationMs).append('\n')
            append("samples=").append(sampleCount).append('\n')
            append("local_progress_samples=").append(progressCount).append('\n')
            append("reconnect_events=").append(reconnectCount).append('\n')
            append("faults=").append(if (faults.isEmpty()) "NONE"
                else faults.joinToString("|")).append('\n')
            append("local_five_minute_trace=")
                .append(if (localPass) "OBSERVED_LOCAL_ONLY" else "INCOMPLETE_OR_INTERRUPTED")
                .append('\n')
            append("field_gate_20=NOT_VERIFIED\n")
            append("optical_latency_seconds=NOT_MEASURED\n")
            append("independent_hardware_review=REQUIRED\n")
            append("no_raw_video_or_identifying_data=true\n")
        }
        rows.append(note)
        completion = rows.toString()
    }

    @Synchronized fun report(): String? = completion
}
