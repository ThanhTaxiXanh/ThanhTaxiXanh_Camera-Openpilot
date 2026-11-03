package vn.thanhtaxixanh.camera.ai

import android.net.Network
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ThreadLocalRandom

/**
 * Independent implementation of the RTP/AVP UDP transport observable in the
 * supplied xADAS 0.2.7 XAPK. No proprietary code or binary is used.
 *
 * The port pair is bound before SETUP and remains open until TEARDOWN/close.
 * Both UDP sockets are bound to the Wi-Fi Network of the dash camera.
 */
class A500sUdpPortPair private constructor(
    val rtp: DatagramSocket,
    val rtcp: DatagramSocket
) : Closeable {
    val rtpPort: Int get() = rtp.localPort
    val rtcpPort: Int get() = rtcp.localPort
    val transportHeader: String
        get() = "RTP/AVP;unicast;client_port=$rtpPort-$rtcpPort"

    companion object {
        fun allocate(network: Network): A500sUdpPortPair {
            var last: Exception? = null
            repeat(32) {
                val evenPort = ThreadLocalRandom.current().nextInt(11000, 29000) * 2
                var a: DatagramSocket? = null
                var b: DatagramSocket? = null
                try {
                    a = DatagramSocket(null)
                    network.bindSocket(a)
                    a.bind(InetSocketAddress(evenPort))
                    a.soTimeout = 100
                    a.receiveBufferSize = 2 * 1024 * 1024

                    b = DatagramSocket(null)
                    network.bindSocket(b)
                    b.bind(InetSocketAddress(evenPort + 1))
                    b.soTimeout = 1100
                    b.receiveBufferSize = 256 * 1024
                    return A500sUdpPortPair(a, b)
                } catch (e: Exception) {
                    last = e
                    a?.close()
                    b?.close()
                }
            }
            throw SocketException("Cannot bind RTP/RTCP UDP on 70mai Wi-Fi: " +
                (last?.message ?: "all candidate ports unavailable"))
        }
    }

    /** The caller owns the receive loop and can reuse [buffer] without allocations. */
    fun receivePacket(buffer: ByteArray): DatagramPacket {
        require(buffer.size >= 1500)
        val packet = DatagramPacket(buffer, buffer.size)
        rtp.receive(packet)
        return packet
    }

    override fun close() {
        rtp.close()
        rtcp.close()
    }
}

/** RTSP Transport header validation. Never treat TCP as UDP silently. */
object RtspTransportNegotiation {
    fun isUdpTransport(header: String): Boolean {
        val h = header.lowercase()
        return h.contains("rtp/avp") && !h.contains("rtp/avp/tcp") &&
            !h.contains("interleaved=")
    }
    fun isTcpTransport(header: String): Boolean =
        header.lowercase().contains("rtp/avp/tcp") ||
            header.lowercase().contains("interleaved=")
}
