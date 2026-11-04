package vn.thanhtaxixanh.camera.ai

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Strict, private ASCII CSV audit of user-supplied model/reference 3D pairs.
 * Declared model hash and instrument identity are UNVERIFIED metadata. A
 * source may not use this parser to set calibration or safety flags.
 */
object ModelAxisFixture {
    const val MAGIC = "TTXAXIS01"
    const val MAX_BYTES = 65536

    data class Evidence(
        val datasetId: String,
        val declaredReferenceInstrumentId: String,
        val declaredModelSha256: String,
        val samples: List<ModelAxisAudit.Sample>
    ) {
        fun audit(): ModelAxisAudit.Report = ModelAxisAudit.inspect(samples)
    }

    fun read(input: InputStream): Evidence {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            require(output.size() + n <= MAX_BYTES) { "Axis fixture exceeds 64 KiB" }
            output.write(buffer, 0, n)
        }
        val bytes = output.toByteArray()
        require(bytes.all {
            val ch = it.toInt() and 255
            ch == 10 || ch == 13 || ch in 32..126
        }) { "Axis fixture must be ASCII CSV" }
        val lines = bytes.toString(Charsets.US_ASCII).lineSequence()
            .map { it.trim() }.filter { it.isNotBlank() }.toList()
        require(lines.size in (2+ModelAxisAudit.MIN_SAMPLES)..
            (2+ModelAxisAudit.MAX_SAMPLES)) { "Need 12..256 paired points" }
        require(lines.first() == MAGIC) { "Invalid TTXAXIS01 header" }
        fun fields(line: String) = line.split(',').map { it.trim() }
        val meta = fields(lines[1])
        val id = Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,79}")
        require(meta.size == 4 && meta[0] == "meta" &&
            id.matches(meta[1]) && id.matches(meta[2]) &&
            Regex("[a-fA-F0-9]{64}").matches(meta[3])) {
            "Invalid model hash or declared dataset/instrument"
        }
        val samples = lines.drop(2).map { line ->
            val row = fields(line)
            require(row.size == 8 && row[0] == "point" &&
                id.matches(row[1])) { "Expected point,id,modelX,Y,Z,realForward,Right,Up" }
            ModelAxisAudit.Sample(
                id = row[1],
                modelForwardRaw = row[2].toDouble(),
                modelLateralRaw = row[3].toDouble(),
                modelVerticalRaw = row[4].toDouble(),
                referenceForwardM = row[5].toDouble(),
                referenceRightM = row[6].toDouble(),
                referenceUpM = row[7].toDouble()
            )
        }
        val report = ModelAxisAudit.inspect(samples)
        require(report.status != ModelAxisAudit.Status.INVALID_DATA &&
            report.status != ModelAxisAudit.Status.INSUFFICIENT_SAMPLES) {
            report.note
        }
        return Evidence(meta[1], meta[2], meta[3].lowercase(), samples)
    }
}
