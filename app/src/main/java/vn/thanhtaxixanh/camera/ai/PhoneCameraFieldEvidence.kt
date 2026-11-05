package vn.thanhtaxixanh.camera.ai

/**
 * Owner-armed Camera2 preview + YUV_420_888 field trace.
 *
 * Never stores pixels, location, device IDs, timestamps of wall-clock events,
 * camera credentials or model output. Image sensor timestamps are compared
 * with elapsedRealtimeNanos ONLY for REALTIME clock sources.
 *
 * Repeating request callbacks do not independently prove that a physical
 * Surface was actually visible. A screenshot/video plus TTXYUV01 review is
 * needed before any hardware acceptance can be considered.
 */
class PhoneCameraFieldEvidence(
    private val requiredMs: Long = 60_000L,
    private val maxTickGapMs: Long = 2_500L,
    private val maxRows: Int = 120
) {
    init {
        require(requiredMs in 5_000L..300_000L)
        require(maxTickGapMs in 500L..10_000L)
        require(maxRows in 8..360)
    }

    enum class Lens { BACK, FRONT, OTHER }
    enum class Clock { REALTIME, UNKNOWN }

    data class Metrics(
        val elapsedMs: Long,
        val captureCallbacks: Long,
        val yuvCallbacks: Long,
        val copiedYuvFrames: Long,
        val badYuvCopies: Long,
        val invalidSensorClocks: Long,
        val staleSensorFrames: Long,
        val peakSensorAgeMs: Long,
        val samples: Int,
        val active: Boolean
    )

    private val lines = StringBuilder(16_000)
    private val faults = linkedSetOf<String>()
    private var beganMs = -1L
    private var lastTickMs = -1L
    private var previousCaptureAt = -1L
    private var previousYuvAt = -1L
    private var previousSampleCaptures = 0L
    private var previousSampleYuv = 0L
    private var lastCaptureProgressAt = -1L
    private var lastYuvProgressAt = -1L
    private var captureCount = 0L
    private var imageCount = 0L
    private var copiedCount = 0L
    private var invalidCopies = 0L
    private var invalidClock = 0L
    private var staleImages = 0L
    private var peakSensorAge = 0L
    private var rowCount = 0
    private var repeatingConfigured = false
    private var sensorClock = Clock.UNKNOWN
    private var finalReport: String? = null

    @Synchronized fun start(
        elapsedMs: Long,
        lens: Lens,
        clock: Clock,
        deviceApi: Int
    ) {
        require(beganMs < 0L && elapsedMs >= 0L && deviceApi > 0)
        beganMs = elapsedMs
        lastTickMs = elapsedMs
        lastCaptureProgressAt = elapsedMs
        lastYuvProgressAt = elapsedMs
        sensorClock = clock
        lines.append("TTXPHONE01\n")
        lines.append("clock=ANDROID_ELAPSED_REALTIME_MS\n")
        lines.append("source=ANDROID_CAMERA2\n")
        lines.append("lens=").append(lens.name).append('\n')
        lines.append("sensor_timestamp_source=").append(clock.name).append('\n')
        lines.append("api_level=").append(deviceApi).append('\n')
        lines.append("requested_yuv_size=640x480\n")
        lines.append("required_ms=").append(requiredMs).append('\n')
        lines.append("camera_preview=REQUIRES_INDEPENDENT_VISUAL_REVIEW\n")
        lines.append("optical_latency=NOT_MEASURED\n")
        lines.append("output=COUNTERS_ONLY_NO_IMAGES_OR_LOCATION\n")
        lines.append("sample:elapsed_ms,capture_callbacks,yuv_callbacks,copied_yuv,bad_copies,sensor_clock_rejections,sensor_old,surface_valid\n")
    }

    @Synchronized fun isActive(): Boolean = beganMs >= 0L && finalReport == null

    @Synchronized fun configured(atMs: Long) {
        if (!isActive()) return
        if (atMs < beganMs) faults.add("CONFIGURED_CLOCK_INVALID")
        repeatingConfigured = true
        lines.append("event:").append((atMs - beganMs).coerceAtLeast(0L))
            .append(",REPEATING_REQUEST_CONFIGURED\n")
    }

    @Synchronized fun capture(atMs: Long) {
        if (!isActive()) return
        if (atMs < beganMs || (previousCaptureAt >= 0 && atMs < previousCaptureAt)) {
            faults.add("CAPTURE_CLOCK_REGRESSION")
            return
        }
        previousCaptureAt = atMs
        captureCount++
    }

    @Synchronized fun image(
        atMs: Long,
        sensorTimestampNs: Long,
        nowElapsedNs: Long
    ) {
        if (!isActive()) return
        if (atMs < beganMs || (previousYuvAt >= 0 && atMs < previousYuvAt)) {
            faults.add("IMAGE_CLOCK_REGRESSION")
            return
        }
        previousYuvAt = atMs
        imageCount++
        // UNKNOWN sensor timebase can still document YUV delivery but cannot
        // claim real sensor-to-receipt age or eligibility for RAW ONNX.
        if (sensorClock != Clock.REALTIME) return
        if (sensorTimestampNs <= 0L || nowElapsedNs <= 0L ||
            sensorTimestampNs > nowElapsedNs) {
            invalidClock++
            return
        }
        val ageNs = nowElapsedNs - sensorTimestampNs
        val ageMs = ageNs / 1_000_000L
        if (ageMs > peakSensorAge) peakSensorAge = ageMs
        if (ageNs > VisionSafetyGate.MAX_FRAME_AGE_MS * 1_000_000L)
            staleImages++
    }

    @Synchronized fun copy(atMs: Long, width: Int, height: Int, valid: Boolean) {
        if (!isActive()) return
        if (atMs < beganMs) {
            faults.add("COPY_CLOCK_INVALID")
            return
        }
        if (!valid || width != 640 || height != 480) {
            invalidCopies++
            return
        }
        copiedCount++
    }

    /**
     * Return true when the trace has sealed. The 1-second UI tick is bounded;
     * no per-frame I/O or copies run on the Camera2 callback.
     */
    @Synchronized fun sample(atMs: Long, surfaceValid: Boolean): Boolean {
        if (!isActive()) return finalReport != null
        if (atMs <= lastTickMs) {
            faults.add("TICK_CLOCK_INVALID")
            finish("INVALID_CLOCK", lastTickMs)
            return true
        }
        if (atMs - lastTickMs > maxTickGapMs) faults.add("TICK_GAP")
        if (!surfaceValid) faults.add("SURFACE_INVALID")
        if (!repeatingConfigured) faults.add("NO_REPEATING_REQUEST")
        if (captureCount > previousSampleCaptures) {
            lastCaptureProgressAt = atMs
        } else if (atMs - lastCaptureProgressAt > 4_000L)
            faults.add("CAPTURE_PROGRESS_STALLED")
        if (imageCount > previousSampleYuv) {
            lastYuvProgressAt = atMs
        } else if (atMs - lastYuvProgressAt > 4_000L)
            faults.add("YUV_PROGRESS_STALLED")
        previousSampleCaptures = captureCount
        previousSampleYuv = imageCount
        lastTickMs = atMs
        rowCount++
        if (rowCount > maxRows) {
            faults.add("ROW_LIMIT")
            finish("ROW_LIMIT", atMs)
            return true
        }
        lines.append("sample:").append(atMs-beganMs).append(',')
            .append(captureCount).append(',')
            .append(imageCount).append(',')
            .append(copiedCount).append(',')
            .append(invalidCopies).append(',')
            .append(invalidClock).append(',')
            .append(staleImages).append(',')
            .append(surfaceValid).append('\n')
        if (atMs-beganMs >= requiredMs) {
            finish("DURATION_REACHED", atMs)
            return true
        }
        return false
    }

    @Synchronized fun stop(atMs: Long, reason: String = "OWNER_STOP") {
        if (!isActive()) return
        if (atMs < lastTickMs) faults.add("STOP_CLOCK_INVALID")
        finish(when(reason) {
            "OWNER_STOP","CAMERA_CLOSED","ACTIVITY_PAUSED","SURFACE_LOST" -> reason
            else -> "OTHER_STOP"
        }, maxOf(atMs,lastTickMs))
    }

    private fun finish(reason: String, atMs: Long) {
        if (finalReport != null) return
        if (!repeatingConfigured) faults.add("NO_REPEATING_REQUEST")
        if (captureCount < 5L) faults.add("INSUFFICIENT_CAPTURE_CALLBACKS")
        if (imageCount < 5L) faults.add("INSUFFICIENT_YUV_CALLBACKS")
        if (copiedCount < 2L) faults.add("INSUFFICIENT_COPIED_YUV")
        val elapsed=(atMs-beganMs).coerceAtLeast(0L)
        val local = reason=="DURATION_REACHED" &&
            elapsed>=requiredMs && faults.isEmpty() &&
            captureCount>=5L && imageCount>=5L && copiedCount>=2L
        lines.append("end_reason=").append(reason).append('\n')
        lines.append("elapsed_ms=").append(elapsed).append('\n')
        lines.append("repeating_request_configured=").append(repeatingConfigured).append('\n')
        lines.append("capture_callbacks=").append(captureCount).append('\n')
        lines.append("yuv_callbacks=").append(imageCount).append('\n')
        lines.append("verified_640x480_yuv_copies=").append(copiedCount).append('\n')
        lines.append("bad_yuv_copies=").append(invalidCopies).append('\n')
        lines.append("invalid_sensor_clock_samples=").append(invalidClock).append('\n')
        lines.append("stale_sensor_frames=").append(staleImages).append('\n')
        lines.append("peak_sensor_receipt_age_ms=")
            .append(if (sensorClock==Clock.REALTIME) peakSensorAge.toString() else "UNKNOWN_TIMEBASE")
            .append('\n')
        lines.append("sample_count=").append(rowCount).append('\n')
        lines.append("faults=").append(if (faults.isEmpty()) "NONE"
            else faults.joinToString("|")).append('\n')
        lines.append("local_camera2_trace=")
            .append(if (local) "OBSERVED_LOCAL_ONLY" else "INCOMPLETE_OR_INTERRUPTED")
            .append('\n')
        lines.append("criterion_8=CODED_PENDING_INDEPENDENT_DEVICE_REVIEW\n")
        lines.append("gate_A_8=NOT_PASS_BY_THIS_TRACE_ALONE\n")
        lines.append("optical_preview_review=REQUIRED\n")
        lines.append("independent_yuv_file_review=REQUIRED\n")
        lines.append("physical_A500S_gate=NOT_TESTED\n")
        finalReport=lines.toString()
    }

    @Synchronized fun report(): String? = finalReport

    @Synchronized fun metrics(nowMs: Long): Metrics =
        Metrics(
            elapsedMs=if (beganMs<0L || nowMs<beganMs) 0L else nowMs-beganMs,
            captureCallbacks=captureCount,yuvCallbacks=imageCount,
            copiedYuvFrames=copiedCount,badYuvCopies=invalidCopies,
            invalidSensorClocks=invalidClock,staleSensorFrames=staleImages,
            peakSensorAgeMs=peakSensorAge,samples=rowCount,
            active=isActive()
        )
}
