package vn.thanhtaxixanh.camera.ai

/**
 * Bounded, owner-initiated Android GPS/PiP evidence trace.
 *
 * Records only boolean permission/provider/freshness, Surface, PiP callbacks
 * and the selected HUD settings. No location coordinates, raw GNSS fixes,
 * camera URLs, video, model tensors or vehicle identities are retained.
 *
 * A PiP request returning true is NOT entry confirmation. Only the platform
 * onPictureInPictureModeChanged(true) callback can mark observed entry.
 */
class GpsPipFieldEvidence(private val maxRows: Int = 900) {
    init { require(maxRows in 8..2_000) }

    enum class Playback { NONE, IJK, DIRECT }
    enum class Event {
        GPS_TOGGLE_ON, GPS_TOGGLE_OFF, GPS_PERMISSION_GRANTED,
        GPS_PERMISSION_DENIED, SETTINGS_RESUMED,
        PIP_REQUESTED, PIP_REQUEST_REJECTED, PIP_REQUEST_RETURNED_TRUE,
        PIP_REQUEST_EXCEPTION, PIP_ENTERED_CALLBACK, PIP_EXITED_CALLBACK,
        PLAYBACK_STOPPED
    }

    data class Snapshot(
        val elapsedMs: Long,
        val gpsRequested: Boolean,
        val gpsPermission: Boolean,
        val gpsProviderEnabled: Boolean,
        val freshSpeedPresent: Boolean,
        val inPip: Boolean,
        val videoSurfaceValid: Boolean,
        val playback: Playback,
        val unitsImperial: Boolean,
        val brandVisible: Boolean
    )

    private val lines = StringBuilder(36_000)
    private val faults = linkedSetOf<String>()
    private var startedAt: Long? = null
    private var previousAt = -1L
    private var rowCount = 0
    private var pipRequests = 0
    private var pipConfirmedEntries = 0
    private var pipConfirmedExits = 0
    private var permissionDenied = 0
    private var freshSpeedSamples = 0
    private var snapshots = 0
    private var finalReport: String? = null

    @Synchronized fun start(atMs: Long) {
        require(startedAt == null && atMs >= 0L)
        startedAt = atMs
        previousAt = atMs
        lines.append("TTXGPIP01\n")
        lines.append("clock=ANDROID_ELAPSED_REALTIME_MS\n")
        lines.append("scope=ANDROID_GPS_PIP_SETTINGS_OBSERVATION_ONLY\n")
        lines.append("no_location_coordinates=true\n")
        lines.append("no_video_or_camera_credentials=true\n")
        lines.append("format=event_or_sample,elapsed_ms,values\n")
        lines.append("event,0,TRACE_STARTED\n")
    }

    @Synchronized fun isActive(): Boolean =
        startedAt != null && finalReport == null

    private fun acceptClock(nowMs: Long): Boolean {
        val start = startedAt ?: return false
        if (!isActive()) return false
        if (nowMs < start || nowMs < previousAt) {
            faults.add("INVALID_MONOTONIC_CLOCK")
            finishInternal("INVALID_CLOCK", previousAt.coerceAtLeast(start))
            return false
        }
        previousAt = nowMs
        return true
    }

    @Synchronized fun event(atMs: Long, event: Event): Boolean {
        if (!acceptClock(atMs)) return false
        val start = startedAt ?: return false
        if (rowCount >= maxRows) {
            faults.add("ROW_LIMIT_REACHED")
            finishInternal("ROW_LIMIT", atMs)
            return false
        }
        rowCount++
        when (event) {
            Event.PIP_REQUESTED -> pipRequests++
            Event.PIP_ENTERED_CALLBACK -> pipConfirmedEntries++
            Event.PIP_EXITED_CALLBACK -> pipConfirmedExits++
            Event.GPS_PERMISSION_DENIED -> permissionDenied++
            else -> Unit
        }
        lines.append("event,").append(atMs-start).append(',')
            .append(event.name).append('\n')
        return true
    }

    @Synchronized fun sample(value: Snapshot): Boolean {
        if (!acceptClock(value.elapsedMs)) return false
        val start = startedAt ?: return false
        if (rowCount >= maxRows) {
            faults.add("ROW_LIMIT_REACHED")
            finishInternal("ROW_LIMIT", value.elapsedMs)
            return false
        }
        rowCount++
        snapshots++
        if (value.gpsRequested && value.gpsPermission &&
            value.gpsProviderEnabled && value.freshSpeedPresent)
            freshSpeedSamples++
        lines.append("sample,").append(value.elapsedMs-start).append(',')
            .append(value.gpsRequested).append(',')
            .append(value.gpsPermission).append(',')
            .append(value.gpsProviderEnabled).append(',')
            .append(value.freshSpeedPresent).append(',')
            .append(value.inPip).append(',')
            .append(value.videoSurfaceValid).append(',')
            .append(value.playback.name).append(',')
            .append(if (value.unitsImperial) "mph_ft" else "kmh_m").append(',')
            .append(value.brandVisible).append('\n')
        return true
    }

    @Synchronized fun stop(atMs: Long, reason: String = "OWNER_STOP") {
        if (!isActive()) return
        if (atMs < previousAt) faults.add("STOP_CLOCK_REVERSED")
        finishInternal(
            when (reason) {
                "OWNER_STOP", "ACTIVITY_DESTROYED", "PLAYBACK_STOPPED" -> reason
                else -> "OTHER_STOP"
            }, maxOf(atMs, previousAt))
    }

    private fun finishInternal(reason: String, atMs: Long) {
        if (finalReport != null) return
        val elapsed = (atMs-(startedAt ?: atMs)).coerceAtLeast(0L)
        lines.append("end_reason=").append(reason).append('\n')
        lines.append("elapsed_ms=").append(elapsed).append('\n')
        lines.append("sample_count=").append(snapshots).append('\n')
        lines.append("fresh_gps_presence_samples=")
            .append(freshSpeedSamples).append('\n')
        lines.append("pip_requests=").append(pipRequests).append('\n')
        lines.append("pip_confirmed_entry_callbacks=")
            .append(pipConfirmedEntries).append('\n')
        lines.append("pip_confirmed_exit_callbacks=")
            .append(pipConfirmedExits).append('\n')
        lines.append("gps_permission_denials=").append(permissionDenied).append('\n')
        lines.append("trace_faults=")
            .append(if (faults.isEmpty()) "NONE"
                else faults.joinToString("|")).append('\n')
        // A text file alone cannot independently verify GPS satellite origin
        // or native Surface compositor timing, even after both callbacks.
        lines.append("criterion_19=NOT_VERIFIED\n")
        lines.append("gate_A_19=CODED_PENDING_INDEPENDENT_DEVICE_REVIEW\n")
        lines.append("gate_B_mandatory=NOT_EVALUATED\n")
        lines.append("independent_phone_review=REQUIRED\n")
        finalReport=lines.toString()
    }

    @Synchronized fun report(): String? = finalReport
}
