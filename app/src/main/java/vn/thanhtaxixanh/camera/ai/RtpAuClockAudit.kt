package vn.thanhtaxixanh.camera.ai

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Independent 90 kHz H264 access-unit RTP clock continuity audit.
 *
 * A single RTP session owns this correlator. 32-bit timestamp wrap is
 * unwrapped using the shortest signed modular difference; the resulting
 * local-origin MediaCodec presentation timestamps are NOT wall-clock,
 * camera exposure time, or an end-to-end latency measurement.
 *
 * All inputs are accepted only on a single packet-assembly thread. The
 * synchronized snapshot is safe to call from Android's UI status ticker.
 */
class RtpAuClockAudit(private val sampleWindow: Int = 128) {
    init { require(sampleWindow in 8..512) }
    companion object {
        private const val RTP_MAX = 0xffffffffL
        private const val RTP_RATE = 90000L
        private const val BASE_PTS_US = 1_000_000L
        const val MAX_FORWARD_STEP_TICKS = 5L * RTP_RATE
        const val MAX_REVERSE_STEP_TICKS = 45_000L // 500ms B-frame tolerance
    }

    enum class Status { VALID, INVALID, TIMESTAMP_DISCONTINUITY, RECEIPT_CLOCK_REGRESSION }
    data class Stamp(
        val status: Status,
        val presentationTimeUs: Long?,
        val rtpStepMs: Double?,
        val receiptStepMs: Long?,
        val pacingErrorMs: Double?,
        val sessionDriftMs: Double?
    ) {
        val acceptable: Boolean get() = status == Status.VALID
    }
    data class Snapshot(
        val observedAccessUnits: Long,
        val rejectedAccessUnits: Long,
        val timestampWraps: Long,
        val lastRtpStepMs: Double?,
        val lastReceiptStepMs: Long?,
        val lastPacingErrorMs: Double?,
        val p95AbsolutePacingErrorMs: Double?,
        val currentSessionDriftMs: Double?
    )

    private var firstRaw: Long? = null
    private var firstArrivalMs = 0L
    private var previousRaw = 0L
    private var previousArrivalMs = 0L
    private var continuousTicks = 0L
    private var observations = 0L
    private var rejected = 0L
    private var wraps = 0L
    private var lastRtpStep: Double? = null
    private var lastReceiptStep: Long? = null
    private var lastPacingError: Double? = null
    private var lastDrift: Double? = null
    private val pacingErrors = ArrayDeque<Double>()

    @Synchronized fun observe(rawTimestamp: Long, receiptAtMs: Long): Stamp {
        fun bad(status: Status): Stamp {
            rejected++
            return Stamp(status,null,null,null,null,null)
        }
        if (rawTimestamp !in 0L..RTP_MAX || receiptAtMs < 0L)
            return bad(Status.INVALID)
        if (firstRaw == null) {
            firstRaw = rawTimestamp
            firstArrivalMs = receiptAtMs
            previousRaw = rawTimestamp
            previousArrivalMs = receiptAtMs
            observations++
            return Stamp(Status.VALID,BASE_PTS_US,null,null,null,0.0)
        }
        if (receiptAtMs < previousArrivalMs)
            return bad(Status.RECEIPT_CLOCK_REGRESSION)
        val modular = (rawTimestamp-previousRaw) and RTP_MAX
        val signed = if (modular >= 0x80000000L)
            modular - 0x100000000L else modular
        if (signed > MAX_FORWARD_STEP_TICKS ||
            signed < -MAX_REVERSE_STEP_TICKS)
            return bad(Status.TIMESTAMP_DISCONTINUITY)

        if (rawTimestamp < previousRaw && signed > 0L) wraps++
        continuousTicks += signed
        val intervalRtpMs = signed.toDouble() * 1000.0 / RTP_RATE
        val intervalReceiptMs = receiptAtMs-previousArrivalMs
        val pace = intervalRtpMs-intervalReceiptMs
        val elapsedRtp = continuousTicks.toDouble()*1000.0/RTP_RATE
        val elapsedReceived = (receiptAtMs-firstArrivalMs).toDouble()
        val drift = elapsedRtp-elapsedReceived
        val ptsUs = BASE_PTS_US + (continuousTicks*1_000_000L/RTP_RATE)
        if (ptsUs < 0L) return bad(Status.TIMESTAMP_DISCONTINUITY)
        previousRaw = rawTimestamp
        previousArrivalMs = receiptAtMs
        observations++
        lastRtpStep = intervalRtpMs
        lastReceiptStep = intervalReceiptMs
        lastPacingError = pace
        lastDrift = drift
        pacingErrors.addLast(abs(pace))
        if (pacingErrors.size > sampleWindow) pacingErrors.removeFirst()
        return Stamp(Status.VALID,ptsUs,intervalRtpMs,
            intervalReceiptMs,pace,drift)
    }

    @Synchronized fun snapshot(): Snapshot {
        val sorted = pacingErrors.sorted()
        val p95 = if (sorted.isEmpty()) null else
            sorted[(ceil(.95*sorted.size).toInt()-1).coerceIn(0,sorted.lastIndex)]
        return Snapshot(observations,rejected,wraps,lastRtpStep,
            lastReceiptStep,lastPacingError,p95,lastDrift)
    }
}
