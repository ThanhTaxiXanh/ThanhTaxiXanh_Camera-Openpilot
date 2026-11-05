package vn.thanhtaxixanh.camera.ai

import java.util.Base64

/**
 * Read H.264 codec configuration only from the selected SDP VIDEO media section.
 *
 * Multiple SDP m= sections and a=fmtp payloads are legal. Searching for the
 * first sprop-parameter-sets anywhere in the SDP can bind MediaCodec to the
 * wrong stream. Never forward corrupted/mismatched CSD to either decoder.
 *
 * This parser does not establish source authenticity or camera-to-glass latency.
 */
object H264SdpParameterSets {
    data class Result(
        val sps: ByteArray?,
        val pps: ByteArray?,
        val payloadType: Int?
    )

    private val videoLine = Regex("^m=video\\s+", RegexOption.IGNORE_CASE)
    private val rtpmap = Regex("^a=rtpmap:(\\d+)\\s+H264/90000(?:\\s|$)", RegexOption.IGNORE_CASE)
    private val anyRtpmap = Regex("^a=rtpmap:(\\d+)\\s+", RegexOption.IGNORE_CASE)
    private val fmtp = Regex("^a=fmtp:(\\d+)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val sprop = Regex("(?:^|;)\\s*sprop-parameter-sets\\s*=\\s*([^;\\s]+)", RegexOption.IGNORE_CASE)

    fun fromSdp(sdp: String): Result {
        val missing = Result(null, null, null)
        if (sdp.length !in 1..65_536) return missing
        val lines = sdp.lineSequence().map { it.trim() }.take(512).toList()
        val start = lines.indexOfFirst { videoLine.containsMatchIn(it) }
        if (start < 0) return missing
        val end = (start + 1 until lines.size).firstOrNull {
            lines[it].startsWith("m=", ignoreCase = true)
        } ?: lines.size
        val media = lines.subList(start, end)
        val payloads = media.first().split(Regex("\\s+")).drop(3)
            .mapNotNull { it.toIntOrNull()?.takeIf { pt -> pt in 0..127 } }
            .distinct()
        if (payloads.isEmpty()) return missing

        val declared = media.mapNotNull { anyRtpmap.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .filter { it in payloads }
        val h264 = media.mapNotNull { rtpmap.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .filter { it in payloads }.distinct()

        // If codecs are explicitly declared and none is H.264, do not
        // infer H.264 from an arbitrary sprop field or another media track.
        if (declared.isNotEmpty() && h264.isEmpty()) return missing
        val candidates = if (h264.isNotEmpty()) h264 else payloads
        for (pt in candidates) {
            val settings = media.asSequence().mapNotNull { line ->
                val match = fmtp.matchEntire(line) ?: return@mapNotNull null
                if (match.groupValues[1].toIntOrNull() != pt) null
                else match.groupValues[2]
            }.firstOrNull() ?: continue
            val raw = sprop.find(settings)?.groupValues?.get(1) ?: continue
            val parts = raw.split(',')
            if (parts.size != 2) continue
            val sps = decode(parts[0]) ?: continue
            val pps = decode(parts[1]) ?: continue
            if (sps.size !in 5..4096 || pps.size !in 2..4096 ||
                (sps[0].toInt() and 0x9f) != 7 ||
                (pps[0].toInt() and 0x9f) != 8 ||
                H264SpsDimensions.parse(sps) == null) continue
            return Result(sps, pps, pt)
        }
        // No trustworthy codec-specific data: the existing A500S 640x480
        // fallback remains explicit, and neither codec receives invalid CSD.
        return Result(null, null, candidates.firstOrNull())
    }

    private fun decode(value: String): ByteArray? =
        if (value.isEmpty() || value.length > 5500) null
        else try { Base64.getDecoder().decode(value) }
        catch (_: IllegalArgumentException) { null }
}
