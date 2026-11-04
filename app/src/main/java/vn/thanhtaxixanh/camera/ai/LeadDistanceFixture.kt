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
    const val TIMED_MAGIC = "TTXLEAD02"
    const val MAX_BYTES = 65536

    data class Evidence(
        val datasetId: String,
        val referenceInstrumentId: String,
        val claimedModelSha256: String,
        val observations: List<LeadDistanceAudit.Observation>,
        val timingAudit: TimedLeadAudit.Report? = null
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
        require(lines.isNotEmpty()) { "Missing lead fixture header" }
        val timed = when (lines[0]) {
            MAGIC -> false
            TIMED_MAGIC -> true
            else -> throw IllegalArgumentException("Invalid TTXLEAD01/02 header")
        }
        val sampleStart = if (timed) 3 else 2
        require(lines.size in (sampleStart + LeadDistanceAudit.MIN_SAMPLES)..
            (sampleStart + LeadDistanceAudit.MAX_SAMPLES)) {
            "Invalid number of lead rows"
        }
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
        var timing: TimedLeadAudit.Report? = null
        val observations = if (timed) {
            val clockRow = split(lines[2])
            require(clockRow.size == 3 && clockRow[0] == "clock" &&
                clockRow[1] == "shared-monotonic-ms" &&
                idPattern.matches(clockRow[2])) {
                "TTXLEAD02 requires declared clock,shared-monotonic-ms,session-id"
            }
            val pairs = lines.drop(sampleStart).map { line ->
                val row = split(line)
                require(row.size == 6 && row[0] == "sample" &&
                    idPattern.matches(row[1])) {
                    "Expected sample,id,referenceAtMs,predictionAtMs,measuredM,predictedM"
                }
                TimedLeadAudit.Sample(
                    row[1],row[2].toLong(),row[3].toLong(),
                    row[4].toDouble(),row[5].toDouble())
            }
            timing = TimedLeadAudit.inspect(pairs)
            require(timing.status ==
                TimedLeadAudit.Status.SYNCHRONIZED_STATISTICS_UNVERIFIED) {
                "Timestamp/range diagnostic rejected: " + timing.status +
                    "; " + timing.note
            }
            pairs.map {
                LeadDistanceAudit.Observation(
                    it.sampleId,it.referenceMeters,it.predictedMeters)
            }
        } else {
            lines.drop(sampleStart).map { line ->
                val row = split(line)
                require(row.size == 4 && row[0] == "sample") {
                    "Expected sample,frame-id,reference-metres,prediction-metres"
                }
                require(idPattern.matches(row[1])) { "Invalid frame identifier" }
                LeadDistanceAudit.Observation(
                    row[1], row[2].toDouble(), row[3].toDouble()
                )
            }
        }
        // Both protocol versions must validate metric range coverage.
        val audited = LeadDistanceAudit.inspect(observations)
        require(audited.status == LeadDistanceAudit.Status.STATISTICS_ONLY_UNVERIFIED) {
            audited.note
        }
        return Evidence(meta[1], meta[2], meta[3].lowercase(),
            observations,timing)
    }
}
