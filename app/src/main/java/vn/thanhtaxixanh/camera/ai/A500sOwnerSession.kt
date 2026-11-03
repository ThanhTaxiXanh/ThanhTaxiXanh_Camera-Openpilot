package vn.thanhtaxixanh.camera.ai

import android.net.Network
import android.os.SystemClock
import org.json.JSONObject
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Owner-approved 70mai DR2500 pairing and local client registration. */
object A500sOwnerProtocol {
    private const val PAIR_SUFFIX = "73VpsAfdety8FDd0"
    fun md5(input: String): String = MessageDigest.getInstance("MD5")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun bindQuery(userId: String): String {
        require(userId.matches(Regex("[0-9]{4,14}")))
        return "BindByBanya.cgi?&-usr=$userId&-signkey=" + md5(userId + PAIR_SUFFIX)
    }
    fun confirmQuery(cameraTimestamp: String): String {
        require(cameraTimestamp.matches(Regex("[0-9]{8,16}")))
        return "UserconfirmByBanya.cgi?&-timestamp=$cameraTimestamp&-signkey=" +
            md5(cameraTimestamp + PAIR_SUFFIX)
    }
    fun registerQuery(localIp: String, unixSeconds: Long, pairedToken: String): String {
        require(localIp.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")))
        require(pairedToken.matches(Regex("[0-9a-fA-F]{32}")))
        require(unixSeconds > 0L)
        val unsigned = "client.cgi?&-operation=register&-ip=$localIp&-timestamp=$unixSeconds"
        return unsigned + "&-signkey=" + md5(unsigned + pairedToken)
    }
}

class A500sOwnerSession(
    private val wifi: Network,
    private val cameraHost: String,
    private val localIp: String,
    private val status: (String) -> Unit
) : Closeable {
    private val stopped = AtomicBoolean(false)
    private var heartbeat: Thread? = null
    @Volatile var sent = 0L
        private set
    @Volatile var ok = 0L
        private set
    @Volatile var failure = ""
        private set

    init { require(cameraHost.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) }

    private fun get(query: String): JSONObject {
        if (stopped.get()) throw InterruptedException("Đã dừng ghép đôi")
        val connection = wifi.openConnection(URL("http://$cameraHost/cgi-bin/$query"))
            as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = 2800
            connection.readTimeout = 2800
            connection.setRequestProperty("_os_", "Android")
            connection.setRequestProperty("_ver_", "1.0.4")
            connection.setRequestProperty("_product_", "70mai")
            connection.setRequestProperty("User-Agent", "TTXCameraAI/1.0")
            if (connection.responseCode != 200)
                error("A500S HTTP " + connection.responseCode)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.length > 8192) error("Phản hồi CGI không hợp lệ")
            JSONObject(body)
        } finally { connection.disconnect() }
    }

    /** Blocking operation. User must confirm pairing on the physical camera. */
    fun pairWithPhysicalConfirmation(userId: String): String {
        val bind = get(A500sOwnerProtocol.bindQuery(userId))
        require(bind.optString("ResultCode") == "0") {
            "A500S từ chối ghép đôi: " + bind.optString("ResultCode")
        }
        val result = bind.getJSONObject("Result")
        val token = result.getString("Token")
        val timestamp = result.getString("timestamp")
        require(token.matches(Regex("[0-9a-fA-F]{32}")))
        status("Hãy bấm nút xác nhận ghép đôi trên A500S")
        val confirmation = A500sOwnerProtocol.confirmQuery(timestamp)
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        while (!stopped.get() && SystemClock.elapsedRealtime() < deadline) {
            val response = get(confirmation)
            when (response.optString("ResultCode")) {
                "0" -> return token
                "-6674" -> Thread.sleep(1700)
                else -> error("Camera từ chối ghép đôi: " +
                    response.optString("ResultCode"))
            }
        }
        error("Chưa xác nhận ghép đôi trong 60 giây")
    }

    /** Periodically register this physically authorized local client. */
    fun beginRegistration(pairedToken: String) {
        require(pairedToken.matches(Regex("[0-9a-fA-F]{32}")))
        if (heartbeat?.isAlive == true) return
        heartbeat = Thread({
            while (!stopped.get()) {
                try {
                    val query = A500sOwnerProtocol.registerQuery(
                        localIp, System.currentTimeMillis() / 1000L, pairedToken)
                    val response = get(query)
                    sent++
                    val result = response.optString("ResultCode")
                    if (result == "0") {
                        ok++
                        failure = ""
                    } else {
                        failure = "A500S register ResultCode=$result"
                        status(failure)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    failure = "Heartbeat " + e.javaClass.simpleName + ": " +
                        (e.message ?: "lỗi mạng").take(90)
                    status(failure)
                }
                try { Thread.sleep(5000L) }
                catch (_: InterruptedException) { break }
            }
        }, "TTX-A500S-Owner-Heartbeat").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        stopped.set(true)
        heartbeat?.interrupt()
        heartbeat = null
    }
}
