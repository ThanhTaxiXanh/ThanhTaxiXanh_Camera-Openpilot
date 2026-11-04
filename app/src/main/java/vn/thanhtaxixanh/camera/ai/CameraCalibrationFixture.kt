package vn.thanhtaxixanh.camera.ai

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Human-readable, LOCAL-only camera geometry diagnostic fixture.
 * The file supplies measurements; parsing MUST NOT set either calibration
 * validation flag, even if a file claims that its points are measured.
 *
 * Simple ASCII CSV avoids a JSON parser dependency in host-unit tests.
 * No embedded video/image, executable content or third-party model data.
 */
object CameraCalibrationFixture {
    const val MAGIC = "TTXCAL01"
    const val LENS_MAGIC = "TTXCAL02"
    const val MAX_FILE_BYTES = 65536
    const val MAX_MARKERS = 256

    data class Evidence(
        val imageWidth: Int,
        val imageHeight: Int,
        val candidate: VisionSafetyGate.Calibration,
        val markers: List<CameraReprojectionAudit.Marker>,
        val lens: LensDistortionProjection.Intrinsics? = null
    ) {
        fun audit(): CameraReprojectionAudit.Report =
            CameraReprojectionAudit.inspect(
                candidate, imageWidth, imageHeight, markers, lens)
    }

    /**
     * TTXCAL01
     * image,640,480
     * candidate,1.5,90,0,0,owner-dataset-id
     * marker,M01,4.0,-1.5,0.0,200,360
     * ...
     * Every column must be present. Measurements must be supplied from an
     * authorized independent source; this decoder never certifies provenance.
     */
    fun read(stream: InputStream): Evidence {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            require(output.size() + n <= MAX_FILE_BYTES) {
                "Calibration fixture exceeds 64 KiB"
            }
            output.write(buffer, 0, n)
        }
        val data = output.toByteArray()
        require(data.all {
            val value = it.toInt() and 0xff
            value == 10 || value == 13 || value in 32..126
        }) { "Fixture must be plain ASCII CSV" }
        val lines = data.toString(Charsets.US_ASCII).lineSequence()
            .map { it.trim() }.filter { it.isNotBlank() }.toList()
        require(lines.isNotEmpty()) { "Missing fixture header" }
        val hasLens = when (lines[0]) {
            MAGIC -> false
            LENS_MAGIC -> true
            else -> throw IllegalArgumentException("Invalid TTXCAL01/02 header")
        }
        val markerStart = if (hasLens) 4 else 3
        require(lines.size in (MIN_MARKERS + markerStart)..(MAX_MARKERS + markerStart)) {
            "Require header, candidate and 8..256 markers"
        }
        fun parts(line: String): List<String> =
            line.split(',').map { it.trim() }
        val image = parts(lines[1])
        require(image.size == 3 && image[0] == "image")
        val width = image[1].toInt()
        val height = image[2].toInt()
        require(width in 2..8192 && height in 2..8192)
        val geom = parts(lines[2])
        require(geom.size == 6 && geom[0] == "candidate")
        val datasetId = geom[5]
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,79}")
            .matches(datasetId)) { "Invalid local dataset ID" }
        val candidate = VisionSafetyGate.Calibration(
            geom[1].toFloat(), geom[2].toFloat(),
            geom[3].toFloat(), geom[4].toFloat(),
            datasetId,
            groundTruthValidated = false,
            modelCoordinateFrameValidated = false
        )
        require(candidate.structurallyValid) {
            "Camera geometry exceeds physical plausibility limits"
        }
        val lens = if (hasLens) {
            // Optional Brown-Conrady fx/fy/cx/cy, radial k1/k2/k3 and
            // tangential p1/p2. No import file can set verified flags.
            val lensRow = parts(lines[3])
            require(lensRow.size == 10 && lensRow[0] == "lens") {
                "Expected lens,fx,fy,cx,cy,k1,k2,p1,p2,k3"
            }
            LensDistortionProjection.Intrinsics(
                width, height,
                lensRow[1].toDouble(), lensRow[2].toDouble(),
                lensRow[3].toDouble(), lensRow[4].toDouble(),
                lensRow[5].toDouble(), lensRow[6].toDouble(),
                lensRow[7].toDouble(), lensRow[8].toDouble(),
                lensRow[9].toDouble()
            ).also { require(it.structurallyValid) {
                "Lens intrinsics/distortion out of bounds"
            } }
        } else null
        val markers = lines.drop(markerStart).map { line ->
            val row = parts(line)
            require(row.size == 7 && row[0] == "marker") {
                "Each marker requires ID, forward, right, up, pixel x/y"
            }
            CameraReprojectionAudit.Marker(
                row[1],
                CameraProjection.Point3(
                    row[2].toDouble(), row[3].toDouble(), row[4].toDouble()),
                CameraProjection.Pixel(
                    row[5].toFloat(), row[6].toFloat())
            )
        }
        return Evidence(width, height, candidate, markers, lens)
    }

    private const val MIN_MARKERS = CameraReprojectionAudit.MIN_ANCHORS
}
