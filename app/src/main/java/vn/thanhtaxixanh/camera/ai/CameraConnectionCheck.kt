package vn.thanhtaxixanh.camera.ai

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets

data class CameraWifi(val network: Network?, val gateway: String?, val detail: String)
data class CameraProbe(val url: String, val status: String, val note: String, val ok: Boolean)

/** RTSP probes only the existing Wi-Fi chosen by the device owner. */
object CameraConnectionCheck {
    fun wifi(cm: ConnectivityManager): CameraWifi {
        val networks = cm.allNetworks.filter {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (networks.isEmpty()) return CameraWifi(null, null,
            "Không có Wi-Fi. Kết nối với hotspot 70mai trong Cài đặt Android; chọn Giữ kết nối khi không có Internet.")
        val items = networks.map { network ->
            val lp = cm.getLinkProperties(network)
            val gateway = lp?.routes?.firstOrNull {
                it.isDefaultRoute && it.gateway is Inet4Address
            }?.gateway?.hostAddress ?: lp?.routes?.firstOrNull {
                it.gateway is Inet4Address
            }?.gateway?.hostAddress
            val ips = lp?.linkAddresses?.mapNotNull { it.address as? Inet4Address }
                ?.mapNotNull { it.hostAddress }.orEmpty()
            Triple(network, gateway, ips)
        }
        val best = items.firstOrNull { it.second == "192.168.0.1" }
            ?: items.firstOrNull { it.second != null } ?: items.first()
        return CameraWifi(best.first, best.second,
            "Wi-Fi IP: " + best.third.joinToString() +
            " | Gateway: " + (best.second ?: "không rõ") +
            if (networks.size > 1) " | Có nhiều Wi-Fi; kiểm tra kết nối Android Auto" else "")
    }

    fun urls(gateway: String, manual: String): List<String> {
        val paths = listOf("livestream/12", "livestream/0", "liveRTSP/av4",
            "liveRTSP/av2", "liveRTSP/av1", "liveRTSP/v1")
        val base = paths.map { "rtsp://" + gateway + ":554/" + it }
        val valid = try {
            val uri = URI(manual)
            uri.scheme.equals("rtsp", true) && uri.host == gateway
        } catch (_: Exception) { false }
        return ((if (valid) listOf(manual) else emptyList()) + base).distinct()
    }

    fun describe(network: Network, url: String): CameraProbe = try {
        val uri = URI(url)
        val host = uri.host ?: error("Thiếu host")
        val port = if (uri.port > 0) uri.port else 554
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 3000)
            socket.soTimeout = 3000
            val request = "DESCRIBE " + url + " RTSP/1.0\r\n" +
                "CSeq: 1\r\nAccept: application/sdp\r\n" +
                "User-Agent: TTXCameraAI/0.2\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
            socket.getOutputStream().flush()
            val input = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
            val status = input.readLine() ?: "Không có phản hồi RTSP"
            val headers = mutableMapOf<String, String>()
            for (i in 0 until 45) {
                val line = input.readLine() ?: break
                if (line.isBlank()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).lowercase()] =
                    line.substring(colon + 1).trim()
            }
            val good = status.contains(" 200 ") &&
                headers["content-type"]?.contains("sdp", true) == true
            val message = when {
                status.contains(" 401 ") || status.contains(" 403 ") ->
                    "Từ chối phiên; kiểm tra ghép nối và xác nhận trên 70mai"
                status.contains(" 404 ") -> "RTSP mở nhưng đường dẫn sai"
                good -> "DESCRIBE OK, nhận SDP; có thể thử phát video"
                else -> "Server phản hồi nhưng không xác nhận được SDP video"
            }
            CameraProbe(url, status, message, good)
        }
    } catch (_: SocketTimeoutException) {
        CameraProbe(url, "TIMEOUT", "Không có phản hồi TCP/RTSP", false)
    } catch (e: Exception) {
        CameraProbe(url, e.javaClass.simpleName, e.message ?: "Lỗi không rõ", false)
    }
}
