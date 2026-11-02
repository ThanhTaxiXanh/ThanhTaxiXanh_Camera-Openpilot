package vn.thanhtaxixanh.camera.ai

/** RTSP/1.0 Session maintenance, pure logic for deterministic JVM tests. */
object RtspSessionMaintenance {
    fun timeoutSeconds(header: String): Int =
        Regex("(?:^|;)\\s*timeout\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(10, 300) ?: 60

    fun intervalMs(timeoutSeconds: Int): Long =
        (timeoutSeconds.coerceIn(10, 300) * 1000L / 3L).coerceIn(5000L, 15000L)

    fun request(
        method: String,
        target: String,
        sessionId: String,
        sequence: Int
    ): ByteArray {
        require(method == "GET_PARAMETER" || method == "SET_PARAMETER") {
            "Only supported keepalive methods are allowed"
        }
        require(sessionId.isNotBlank() && sessionId.none { it == '\r' || it == '\n' })
        require(target.startsWith("rtsp://") && target.none { it == '\r' || it == '\n' })
        require(sequence > 0)
        return buildString {
            append(method).append(' ').append(target).append(" RTSP/1.0\r\n")
            append("CSeq: ").append(sequence).append("\r\n")
            append("Session: ").append(sessionId).append("\r\n")
            append("User-Agent: TTXCameraAI-Direct/1.0\r\n")
            append("Content-Length: 0\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
    }
}
