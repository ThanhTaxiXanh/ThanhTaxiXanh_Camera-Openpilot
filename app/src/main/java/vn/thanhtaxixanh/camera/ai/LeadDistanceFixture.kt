package vn.thanhtaxixanh.camera.ai

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Local-only TTXLEAD01 ASCII reference-vs-prediction evidence; never
 * evaluates a live ONNX model or interprets uncalibrated output in metres.
 *
 * dataset/source/modelHash are USER-SUPPLIED metadata, not independently
 * authenticated provenance. Source should name a real external range
 * measurement device/protocol, never the unverified xADAS lead tensor.
 */
object LeadDistanceFixture {
    const val MAGIC = "TTXLEAD01"
    const val MAX_BYTES = 65536

    data class Evidence(
        val datasetId: String,
        val referenceInstrumentId: String,
        val claimedModelSha256: String,
        val observations: List<LeadDistanceAudit.Observation>
    ) {
        fun audit(): LeadDistanceAudit.Report =
            LeadDistanceAudit.inspect(observations)
    }

    /** CSV:
     * TTXLEAD01
     * meta,dataset-id,reference-instrument-id,64-hex-model-sha256
     * sample,frame-id,measured-metres,predicted-metres
     * ... 12-512 samples
     */
    fun read(input: InputStream): Evidence {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            require(out.size() + n <= MAX_BYTES) { "Lead fixture exceeds 64 KiB" }
            out.write(buffer, 0, n)
        }
        val raw = out.toByteArray()
        require(raw.all {
            val byte = it.toInt() and 255
            byte == 10 || byte == 13 || byte in 32..126
        }) { "Lead fixture must be ASCII" }
        val lines = raw.toString(Charsets.US_ASCII).lineSequence()
            .map { it.trim() }.filter { it.isNotBlank() }.toList()
        require(lines.size in (2 + LeadDistanceAudit.MIN_SAMPLES)..
            (2 + LeadDistanceAudit.MAX_SAMPLES)) { "Invalid number of lead rows" }
        require(lines[0] == MAGIC) { "Invalid TTXLEAD01 header" }
        fun split(line: String) = line.split(',').map { it.trim() }
        val meta = split(lines[1])
        require(meta.size == 4 && meta[0] == "meta") { "Expected meta row" }
        val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,79}")
        require(idPattern.matches(meta[1]) && idPattern.matches(meta[2])) {
            "Invalid dataset or independent instrument identifier"
        }
        require(Regex("[a-fA-F0-9]{64}").matches(meta[3])) {
            "Model SHA-256 metadata must be 64 hexadecimal digits"
        }
        val observations = lines.drop(2).map { line ->
            val row = split(line)
            require(row.size == 4 && row[0] == "sample") {
                "Expected sample,frame-id,reference-metres,prediction-metres"
            }
            require(idPattern.matches(row[1])) { "Invalid frame identifier" }
            LeadDistanceAudit.Observation(
                row[1], row[2].toDouble(), row[3].toDouble()
            )
        }
        // Parse is strict: do not claim to have independent evidence
        // unless its structural/coverage requirements are satisfied.
        val audited = LeadDistanceAudit.inspect(observations)
        require(audited.status == LeadDistanceAudit.Status.STATISTICS_ONLY_UNVERIFIED) {
            audited.note
        }
        return Evidence(meta[1], meta[2], meta[3].lowercase(), observations)
    }
}
