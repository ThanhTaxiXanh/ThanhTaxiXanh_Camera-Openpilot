package vn.thanhtaxixanh.camera.ai

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Offline statistical comparison against INDEPENDENTLY measured lead ranges.
 * predictedMeters is explicitly provided by an external, independently
 * authorized metric conversion pipeline. Never interpret raw xADAS lead
 * tensor indices as metres or silently derive predictions from image pixels.
 *
 * Good statistics on synthetic/forged data DO NOT prove sensor accuracy.
 * This module cannot authorize warning engine, HUD or model calibration.
 */
object LeadDistanceAudit {
    const val MIN_SAMPLES = 12
    const val MAX_SAMPLES = 512
    const val MIN_DISTANCE_SPAN_M = 30.0
    const val MIN_DISTANCE_BANDS = 3

    data class Observation(
        val sampleId: String,
        val referenceMeters: Double,
        val predictedMeters: Double
    )

    enum class Status {
        INVALID_DATA, INSUFFICIENT, POOR_RANGE_COVERAGE,
        STATISTICS_ONLY_UNVERIFIED
    }

    data class Report(
        val status: Status,
        val count: Int,
        val referenceRangeMeters: Double?,
        val meanAbsoluteErrorMeters: Double?,
        val rootMeanSquareErrorMeters: Double?,
        val p95AbsoluteErrorMeters: Double?,
        val meanAbsolutePercentError: Double?,
        val signedBiasMeters: Double?,
        val note: String
    ) {
        val mayEnableAdas: Boolean get() = false
    }

    private fun band(distanceMeters: Double): Int = when {
        distanceMeters < 15.0 -> 0
        distanceMeters < 30.0 -> 1
        distanceMeters < 60.0 -> 2
        else -> 3
    }

    fun inspect(observations: List<Observation>): Report {
        fun no(status: Status, note: String) = Report(
            status, observations.size, null, null, null, null, null, null,
            note)
        if (observations.size > MAX_SAMPLES ||
            observations.any { m ->
                m.sampleId.isBlank() || m.sampleId.length > 80 ||
                    !m.referenceMeters.isFinite() ||
                    !m.predictedMeters.isFinite() ||
                    m.referenceMeters !in 1.0..200.0 ||
                    m.predictedMeters !in 1.0..200.0
            } || observations.map { it.sampleId }.toSet().size != observations.size) {
            return no(Status.INVALID_DATA, "Invalid/duplicate independent range observations")
        }
        if (observations.size < MIN_SAMPLES) return no(
            Status.INSUFFICIENT, "Need at least $MIN_SAMPLES independent paired observations")

        val minimum = observations.minOf { it.referenceMeters }
        val maximum = observations.maxOf { it.referenceMeters }
        if (maximum - minimum < MIN_DISTANCE_SPAN_M ||
            observations.map { band(it.referenceMeters) }.toSet().size <
                MIN_DISTANCE_BANDS) {
            return no(Status.POOR_RANGE_COVERAGE,
                "Need at least 30 metres of span across three distance bands")
        }
        val errors = observations.map { abs(it.predictedMeters - it.referenceMeters) }
            .sorted()
        val signed = observations.sumOf { it.predictedMeters - it.referenceMeters }
        val mae = errors.average()
        val rmse = sqrt(errors.sumOf { it * it } / errors.size)
        val p95 = errors[(ceil(0.95 * errors.size).toInt() - 1)
            .coerceIn(0, errors.lastIndex)]
        val mape = observations.sumOf {
            100.0 * abs(it.predictedMeters-it.referenceMeters) / it.referenceMeters
        } / observations.size

        return Report(
            Status.STATISTICS_ONLY_UNVERIFIED, observations.size,
            maximum - minimum, mae, rmse, p95, mape, signed / observations.size,
            "Numeric discrepancy only; model axis, ground truth provenance, " +
                "range timestamp synchrony and real A500S calibration unverified"
        )
    }
}
