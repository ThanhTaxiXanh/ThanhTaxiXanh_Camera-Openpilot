package vn.thanhtaxixanh.camera.ai

import android.net.Network
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A non-mutating RTSP DESCRIBE discovery for camera Wi-Fi networks.
 * Candidate paths were observed in the owner's xADAS XAPK; a 200 response
 * only proves SDP support, NOT successful PLAY, correct H264, or rear camera.
 */
object RtspCandidateDiscovery {
    val paths = listOf(
        "/livestream/12",
        "/livestream/0",
        "/liveRTSP/av4",
        "/liveRTSP/av2",
        "/liveRTSP/av1",
        "/liveRTSP/v1"
    )
    data class Candidate(val url: String, val response: String)

    fun probe(network: Network, host: String, path: String): Candidate? {
        require(path in paths)
        require(host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")))
        val rtsp = "rtsp://" + host + ":554" + path
        val socket: Socket = network.socketFactory.createSocket()
        return try {
            socket.connect(InetSocketAddress(host, 554), 1200)
            socket.soTimeout = 1800
            val query = "DESCRIBE " + rtsp + " RTSP/1.0\r\n" +
                "CSeq: 1\r\nAccept: application/sdp\r\n" +
                "User-Agent: TTXCameraAI\r\n\r\n"
            socket.getOutputStream().write(query.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val bytes = ByteArray(2048)
            val n = socket.getInputStream().read(bytes)
            if (n <= 0) return null
            val head = String(bytes, 0, n, Charsets.US_ASCII)
            val first = head.lineSequence().firstOrNull()?.trim().orEmpty()
            if (first.startsWith("RTSP/1.0 200"))
                Candidate(rtsp, first)
            else null
        } catch (_: Exception) {
            null
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun findFirst(network: Network, host: String): Candidate? {
        for (path in paths) {
            val found = probe(network, host, path)
            if (found != null) return found
        }
        return null
    }
}
