package vn.thanhtaxixanh.camera.ai

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Pure offline chronology audit of independently timestamped metric ranges.
 *
 * refTimeMs and predictionTimeMs MUST be in the SAME device/session
 * monotonic clock domain. File-declared timebase cannot establish this.
 * Camera RTP receipt time is NOT optical exposure time, so even a passing
 * sample-pair clock audit cannot measure glass-to-glass latency.
 */
object TimedLeadAudit {
    const val MAX_SKEW_MS = 100L
    const val MAX_TIMESTAMP_MS = 315_360_000_000L // 10 years monotonic uptime

    data class Sample(
        val sampleId: String,
        val referenceAtMs: Long,
        val predictionAtMs: Long,
        val referenceMeters: Double,
        val predictedMeters: Double
    )

    enum class Status {
        INVALID_TIMESTAMPS, UNORDERED_TIMESTAMPS, EXCESSIVE_SKEW,
        INSUFFICIENT_OR_BAD_RANGES, SYNCHRONIZED_STATISTICS_UNVERIFIED
    }

    data class Report(
        val status: Status,
        val samples: Int,
        val maxSkewMs: Long?,
        val p95SkewMs: Long?,
        val distanceAudit: LeadDistanceAudit.Report?,
        val note: String
    ) {
        val mayEnableAdas: Boolean get() = false
    }

    fun inspect(samples: List<Sample>): Report {
        fun no(status: Status, note: String) =
            Report(status, samples.size, null, null, null, note)
        if (samples.size !in LeadDistanceAudit.MIN_SAMPLES..
                LeadDistanceAudit.MAX_SAMPLES ||
            samples.map { it.sampleId }.distinct().size != samples.size ||
            samples.any {
                it.sampleId.isBlank() || it.sampleId.length > 80 ||
                    it.referenceAtMs !in 0L..MAX_TIMESTAMP_MS ||
                    it.predictionAtMs !in 0L..MAX_TIMESTAMP_MS
            }) return no(Status.INVALID_TIMESTAMPS,
            "Need unique IDs and finite bounded timestamps from one declared clock")
        for (i in 1 until samples.size) {
            if (samples[i].referenceAtMs <= samples[i-1].referenceAtMs ||
                samples[i].predictionAtMs <= samples[i-1].predictionAtMs) {
                return no(Status.UNORDERED_TIMESTAMPS,
                    "Reference and prediction timestamps must both strictly increase")
            }
        }
        val skews = samples.map {
            abs(it.referenceAtMs-it.predictionAtMs)
        }.sorted()
        val maxSkew = skews.last()
        val p95Skew = skews[(ceil(skews.size*0.95).toInt()-1)
            .coerceIn(0,skews.lastIndex)]
        if (maxSkew > MAX_SKEW_MS) {
            return Report(Status.EXCESSIVE_SKEW,samples.size,
                maxSkew,p95Skew,null,
                "Pair skew exceeds 100ms; comparing unmatched positions is unsafe")
        }
        val ranges = LeadDistanceAudit.inspect(samples.map {
            LeadDistanceAudit.Observation(
                it.sampleId,it.referenceMeters,it.predictedMeters)
        })
        if (ranges.status != LeadDistanceAudit.Status.STATISTICS_ONLY_UNVERIFIED)
            return Report(Status.INSUFFICIENT_OR_BAD_RANGES,samples.size,
                maxSkew,p95Skew,null,ranges.note)
        return Report(
            Status.SYNCHRONIZED_STATISTICS_UNVERIFIED,samples.size,
            maxSkew,p95Skew,ranges,
            "Same-clock timestamps are self-declared, not independently " +
                "verified; no exposure-time, optical, axis or range certification"
        )
    }
}
