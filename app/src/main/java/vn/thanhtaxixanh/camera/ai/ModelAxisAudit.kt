package vn.thanhtaxixanh.camera.ai

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Offline hypothesis test for the sign convention of raw lane coordinates.
 *
 * modelForwardRaw/lateralRaw/verticalRaw are an *unverified* independent
 * model interpretation supplied with matched measured 3D reference points.
 * This DOES NOT turn raw xADAS/Openpilot tensors into physical metres.
 *
 * Fitting a synthetic fixture to itself cannot establish model provenance.
 * Even an unambiguous/low-error diagnostic NEVER authorizes live ADAS.
 */
object ModelAxisAudit {
    const val MIN_SAMPLES = 12
    const val MAX_SAMPLES = 256
    const val MIN_FORWARD_SPAN_M = 20.0
    const val MIN_LATERAL_SPAN_M = 2.0
    const val MIN_VERTICAL_SPAN_M = 0.5
    const val MAX_MATCH_RMS_M = 1.0
    const val MIN_WINNER_MARGIN_RMS_M = 0.5

    data class Sample(
        val id: String,
        val modelForwardRaw: Double,
        val modelLateralRaw: Double,
        val modelVerticalRaw: Double,
        val referenceForwardM: Double,
        val referenceRightM: Double,
        val referenceUpM: Double
    )

    enum class Hypothesis(val rightSign: Int, val upSign: Int) {
        RIGHT_POSITIVE_UP_POSITIVE(1, 1),
        RIGHT_POSITIVE_UP_NEGATIVE(1, -1),
        RIGHT_NEGATIVE_UP_POSITIVE(-1, 1),
        RIGHT_NEGATIVE_UP_NEGATIVE(-1, -1)
    }

    enum class Status {
        INVALID_DATA, INSUFFICIENT_SAMPLES, INSUFFICIENT_COVERAGE,
        HIGH_RESIDUAL, AMBIGUOUS_AXES, MATCH_UNVERIFIED
    }

    data class Report(
        val status: Status,
        val count: Int,
        val bestHypothesis: Hypothesis?,
        val rmsMeters: Double?,
        val runnerUpRmsMeters: Double?,
        val note: String
    ) {
        val mayEnableAdas: Boolean get() = false
    }

    fun inspect(samples: List<Sample>): Report {
        fun no(status: Status, why: String) =
            Report(status, samples.size, null, null, null, why)
        if (samples.size > MAX_SAMPLES ||
            samples.map { it.id }.distinct().size != samples.size ||
            samples.any { s ->
                s.id.isBlank() || s.id.length > 80 ||
                    !listOf(
                        s.modelForwardRaw, s.modelLateralRaw, s.modelVerticalRaw,
                        s.referenceForwardM, s.referenceRightM, s.referenceUpM
                    ).all { it.isFinite() } ||
                    s.modelForwardRaw !in 0.01..250.0 ||
                    s.referenceForwardM !in 0.01..250.0 ||
                    abs(s.modelLateralRaw) > 50.0 ||
                    abs(s.referenceRightM) > 50.0 ||
                    abs(s.modelVerticalRaw) > 20.0 ||
                    abs(s.referenceUpM) > 20.0
            }) return no(Status.INVALID_DATA, "Invalid, duplicate or non-finite paired 3D points")

        if (samples.size < MIN_SAMPLES) return no(
            Status.INSUFFICIENT_SAMPLES, "At least 12 synchronized 3D pairs required")
        fun span(values: List<Double>) = values.maxOrNull()!! - values.minOrNull()!!
        val forwardSpan = span(samples.map { it.referenceForwardM })
        val lateralSpan = span(samples.map { it.modelLateralRaw })
        val verticalSpan = span(samples.map { it.modelVerticalRaw })
        if (forwardSpan < MIN_FORWARD_SPAN_M ||
            lateralSpan < MIN_LATERAL_SPAN_M ||
            verticalSpan < MIN_VERTICAL_SPAN_M ||
            samples.none { it.referenceRightM > 0.5 } ||
            samples.none { it.referenceRightM < -0.5 }) {
            return no(Status.INSUFFICIENT_COVERAGE,
                "Need forward, left/right and vertical diversity to discriminate axis signs")
        }

        val scored = Hypothesis.entries.map { candidate ->
            val squared = samples.sumOf { sample ->
                val dx = sample.modelForwardRaw - sample.referenceForwardM
                val dy = candidate.rightSign * sample.modelLateralRaw -
                    sample.referenceRightM
                val dz = candidate.upSign * sample.modelVerticalRaw -
                    sample.referenceUpM
                dx*dx + dy*dy + dz*dz
            }
            Pair(candidate, sqrt(squared / samples.size))
        }.sortedBy { it.second }

        val first = scored[0]
        val second = scored[1]
        val status = when {
            first.second > MAX_MATCH_RMS_M -> Status.HIGH_RESIDUAL
            second.second - first.second < MIN_WINNER_MARGIN_RMS_M ->
                Status.AMBIGUOUS_AXES
            else -> Status.MATCH_UNVERIFIED
        }
        return Report(
            status, samples.size, first.first, first.second, second.second,
            "Synthetic or manually matched data is not proof of camera calibration, " +
                "raw model axis semantics or measured physical coordinates"
        )
    }
}
