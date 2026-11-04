package vn.thanhtaxixanh.camera.ai

/**
 * Single-session, thread-safe LOCAL VIDEO PROGRESS watchdog.
 *
 * Only stages that are expected to advance during playback can cause
 * reconnect. Android's OnFrameRenderedListener is intentionally omitted:
 * some vendor decoders delay, batch, or never deliver render callbacks.
 *
 * RTP timestamps are session-normalized microseconds, not camera exposure
 * time. Isolated B-frame PTS regressions are permitted; an AU stream whose
 * *highest* observed PTS does not advance may represent a frozen source.
 *
 * The health report is a diagnostic, not ADAS safety evidence.
 */
class DirectStreamHealth(
    private val startupGraceMs: Long = 12_000L,
    private val rtpSilenceMs: Long = 12_000L,
    private val auSilenceMs: Long = 6_000L,
    private val decoderSilenceMs: Long = 5_000L,
    private val surfaceSilenceMs: Long = 5_000L,
    private val frozenPtsMs: Long = 3_000L,
    private val minFrozenAuCount: Long = 8L
) {
    init {
        require(startupGraceMs in 1000L..120_000L)
        require(rtpSilenceMs in 1000L..120_000L)
        require(auSilenceMs in 1000L..120_000L)
        require(decoderSilenceMs in 1000L..120_000L)
        require(surfaceSilenceMs in 1000L..120_000L)
        require(frozenPtsMs in 1000L..120_000L)
        require(minFrozenAuCount in 2L..1000L)
    }

    enum class Status {
        STARTING, FLOWING, INVALID_TIMEBASE,
        RTP_SILENT, AU_STALLED_WITH_RTP,
        SOURCE_PTS_FROZEN, CODEC_OUTPUT_STALLED,
        SURFACE_RELEASE_STALLED
    }

    data class Snapshot(
        val status: Status,
        val lastRtpAgoMs: Long?,
        val lastAuAgoMs: Long?,
        val lastDecodeAgoMs: Long?,
        val lastSurfaceAgoMs: Long?,
        val nonAdvancingAuCount: Long,
        val duplicatePts: Long,
        val backwardPts: Long,
        val invalidClocks: Long,
        val note: String =
            "Local progress only; no exposure/screen latency or ADAS authorization"
    ) {
        val requiresReconnect: Boolean get() = when (status) {
            Status.RTP_SILENT, Status.AU_STALLED_WITH_RTP,
            Status.SOURCE_PTS_FROZEN, Status.CODEC_OUTPUT_STALLED,
            Status.SURFACE_RELEASE_STALLED -> true
            else -> false
        }
    }

    private var startedAt = -1L
    private var lastRtp = -1L
    private var lastAu = -1L
    private var lastDecode = -1L
    private var lastSurface = -1L
    private var maxPts = -1L
    private var lastPts = -1L
    private var lastPtsAdvanceAt = -1L
    private var nonAdvancingCount = 0L
    private var duplicate = 0L
    private var backward = 0L
    private var invalid = 0L

    @Synchronized fun start(atMs: Long): Boolean {
        if (atMs < 0 || startedAt >= 0) return false
        startedAt = atMs
        return true
    }

    private fun accept(now: Long, previous: Long): Boolean {
        if (startedAt < 0L || now < startedAt ||
            (previous >= 0L && now < previous)) {
            invalid++
            return false
        }
        return true
    }

    @Synchronized fun onRtp(atMs: Long): Boolean {
        if (!accept(atMs,lastRtp)) return false
        lastRtp = atMs
        return true
    }

    @Synchronized fun onAu(ptsUs: Long, atMs: Long): Boolean {
        if (ptsUs < 0L || !accept(atMs,lastAu)) return false.also {
            invalid++
        }
        lastAu = atMs
        if (maxPts < 0L || ptsUs > maxPts) {
            maxPts = ptsUs
            lastPtsAdvanceAt = atMs
            nonAdvancingCount = 0L
        } else {
            nonAdvancingCount++
        }
        if (lastPts >= 0L) {
            if (ptsUs == lastPts) duplicate++
            if (ptsUs < lastPts) backward++
        }
        lastPts = ptsUs
        return true
    }

    @Synchronized fun onDecoded(atMs: Long): Boolean {
        if (!accept(atMs,lastDecode)) return false
        lastDecode = atMs
        return true
    }

    @Synchronized fun onSurfaceReleased(atMs: Long): Boolean {
        if (!accept(atMs,lastSurface)) return false
        lastSurface = atMs
        return true
    }

    @Synchronized fun snapshot(nowMs: Long): Snapshot {
        fun ago(t: Long): Long? =
            if (t < 0L || nowMs < t) null else nowMs-t
        val elapsed = ago(startedAt)
        val lastRtpAgo = ago(lastRtp)
        val lastAuAgo = ago(lastAu)
        val lastDecodeAgo = ago(lastDecode)
        val lastSurfaceAgo = ago(lastSurface)
        val status = when {
            elapsed == null -> Status.INVALID_TIMEBASE
            elapsed <= startupGraceMs &&
                (lastAu < 0L || lastDecode < 0L || lastSurface < 0L) ->
                Status.STARTING
            lastRtpAgo == null && elapsed > startupGraceMs ->
                Status.RTP_SILENT
            lastRtpAgo != null && lastRtpAgo > rtpSilenceMs ->
                Status.RTP_SILENT
            lastAuAgo == null && elapsed > startupGraceMs ->
                Status.AU_STALLED_WITH_RTP
            lastAuAgo != null && lastAuAgo > auSilenceMs &&
                lastRtpAgo != null && lastRtpAgo <= auSilenceMs ->
                Status.AU_STALLED_WITH_RTP
            lastAuAgo != null && lastAuAgo <= auSilenceMs &&
                ago(lastPtsAdvanceAt)?.let { it > frozenPtsMs } == true &&
                nonAdvancingCount >= minFrozenAuCount ->
                Status.SOURCE_PTS_FROZEN
            lastAuAgo != null && lastAuAgo <= decoderSilenceMs &&
                lastDecodeAgo == null && elapsed > startupGraceMs ->
                Status.CODEC_OUTPUT_STALLED
            lastAuAgo != null && lastAuAgo <= decoderSilenceMs &&
                lastDecodeAgo != null && lastDecodeAgo > decoderSilenceMs ->
                Status.CODEC_OUTPUT_STALLED
            lastDecodeAgo != null && lastDecodeAgo <= surfaceSilenceMs &&
                lastSurfaceAgo == null && elapsed > startupGraceMs ->
                Status.SURFACE_RELEASE_STALLED
            lastDecodeAgo != null && lastDecodeAgo <= surfaceSilenceMs &&
                lastSurfaceAgo != null && lastSurfaceAgo > surfaceSilenceMs ->
                Status.SURFACE_RELEASE_STALLED
            lastSurface < 0L -> Status.STARTING
            else -> Status.FLOWING
        }
        return Snapshot(status,lastRtpAgo,lastAuAgo,lastDecodeAgo,
            lastSurfaceAgo,nonAdvancingCount,duplicate,backward,invalid)
    }
}
