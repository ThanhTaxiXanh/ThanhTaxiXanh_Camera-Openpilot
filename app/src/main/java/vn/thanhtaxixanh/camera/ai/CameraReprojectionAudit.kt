package vn.thanhtaxixanh.camera.ai

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Pure offline audit of independently measured road markers against a
 * candidate pinhole camera geometry. This is not a camera calibrator.
 *
 * A small reprojection error checks internal geometric consistency only.
 * Synthetic or user-invented anchors can trivially match a wrong camera;
 * therefore even a GEOMETRY_CONSISTENT result NEVER sets Calibration.ready,
 * trusted model axes, metric lead distances or warning permissions.
 */
object CameraReprojectionAudit {
    const val MIN_ANCHORS = 8
    const val MIN_X_SPAN_RATIO = 0.20
    const val MIN_Y_SPAN_RATIO = 0.10
    const val MIN_FORWARD_SPAN_METERS = 10.0
    const val MAX_RMS_ERROR_PX = 4.0
    const val MAX_P95_ERROR_PX = 8.0

    data class Marker(
        val id: String,
        val point: CameraProjection.Point3,
        val pixel: CameraProjection.Pixel
    )
    enum class Status {
        INVALID_INPUT, INSUFFICIENT, NARROW_DISTRIBUTION,
        OUT_OF_VIEW, HIGH_REPROJECTION_ERROR, GEOMETRY_CONSISTENT_UNVERIFIED
    }
    data class Report(
        val status: Status,
        val inputMarkers: Int,
        val rmsErrorPx: Double?,
        val p95ErrorPx: Double?,
        val maxErrorPx: Double?,
        val horizontalCoverage: Double?,
        val verticalCoverage: Double?,
        val note: String
    ) {
        // Deliberately cannot authorize any HUD or ADAS state.
        val mayEnableAdas: Boolean get() = false
    }

    fun inspect(
        candidate: VisionSafetyGate.Calibration,
        imageWidth: Int,
        imageHeight: Int,
        markers: List<Marker>
    ): Report {
        fun invalid(status: Status, description: String) = Report(
            status, markers.size, null, null, null, null, null, description)
        if (imageWidth !in 2..8192 || imageHeight !in 2..8192 ||
            !candidate.structurallyValid || markers.size > 256)
            return invalid(Status.INVALID_INPUT, "Invalid candidate, image size or marker count")
        if (markers.size < MIN_ANCHORS)
            return invalid(Status.INSUFFICIENT, "At least $MIN_ANCHORS measured markers required")

        val ids = HashSet<String>()
        val worlds = HashSet<CameraProjection.Point3>()
        for (m in markers) {
            if (m.id.isBlank() || m.id.length > 80 || !ids.add(m.id) ||
                !worlds.add(m.point) ||
                !m.point.forwardM.isFinite() || m.point.forwardM <= 0.0 ||
                !m.point.rightM.isFinite() || !m.point.upM.isFinite() ||
                !m.pixel.x.isFinite() || !m.pixel.y.isFinite() ||
                m.pixel.x !in 0f..<imageWidth.toFloat() ||
                m.pixel.y !in 0f..<imageHeight.toFloat()) {
                return invalid(Status.INVALID_INPUT,
                    "Non-finite, duplicate, behind-camera or off-image marker")
            }
        }

        val minX = markers.minOf { it.pixel.x.toDouble() }
        val maxX = markers.maxOf { it.pixel.x.toDouble() }
        val minY = markers.minOf { it.pixel.y.toDouble() }
        val maxY = markers.maxOf { it.pixel.y.toDouble() }
        val widthCoverage = (maxX - minX) / imageWidth
        val heightCoverage = (maxY - minY) / imageHeight
        val forwardMin = markers.minOf { it.point.forwardM }
        val forwardMax = markers.maxOf { it.point.forwardM }
        if (widthCoverage < MIN_X_SPAN_RATIO ||
            heightCoverage < MIN_Y_SPAN_RATIO ||
            forwardMax - forwardMin < MIN_FORWARD_SPAN_METERS) {
            return Report(Status.NARROW_DISTRIBUTION, markers.size,
                null, null, null, widthCoverage, heightCoverage,
                "Markers are not spatially distributed; no geometry conclusion")
        }

        val errors = DoubleArray(markers.size)
        markers.forEachIndexed { i, marker ->
            val projected = CameraProjection.project(
                marker.point, candidate, imageWidth, imageHeight,
                requireVerifiedCalibration = false
            ) ?: return Report(Status.OUT_OF_VIEW, markers.size,
                null, null, null, widthCoverage, heightCoverage,
                "Candidate projects marker outside visible image or behind camera")
            errors[i] = hypot(
                (projected.x - marker.pixel.x).toDouble(),
                (projected.y - marker.pixel.y).toDouble()
            )
        }
        val rms = sqrt(errors.sumOf { it * it } / errors.size)
        errors.sort()
        val p95 = errors[(ceil(.95 * errors.size).toInt() - 1)
            .coerceIn(0, errors.lastIndex)]
        val maximum = errors.last()
        val matched = rms <= MAX_RMS_ERROR_PX && p95 <= MAX_P95_ERROR_PX
        return Report(
            if (matched) Status.GEOMETRY_CONSISTENT_UNVERIFIED
            else Status.HIGH_REPROJECTION_ERROR,
            markers.size, rms, p95, maximum, widthCoverage, heightCoverage,
            if (matched)
                "Geometric reprojection only; marker provenance, xADAS model " +
                    "coordinates and lead-distance physics remain unverified"
            else "Measured pixel residual exceeds geometry diagnostic tolerance"
        )
    }
}
