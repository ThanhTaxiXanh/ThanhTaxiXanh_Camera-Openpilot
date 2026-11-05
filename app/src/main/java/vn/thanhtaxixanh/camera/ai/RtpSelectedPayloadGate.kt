package vn.thanhtaxixanh.camera.ai

import java.util.concurrent.atomic.AtomicLong

/**
 * RTSP DESCRIBE selects one H264 video RTP payload type for SETUP.
 *
 * TCP interleaved channels and UDP sockets do not themselves bind an RTP
 * payload type. In particular, a foreign PT must never switch the H264
 * assembler or UDP reorder queue into a different source epoch.
 *
 * Malformed RTP is intentionally handed to the downstream assembler: silently
 * ignoring a damaged packet in the selected stream could conceal a missing
 * H264 reference picture. Foreign but structurally readable PTs are ignored.
 *
 * No camera authentication or optical latency is asserted by this gate.
 */
class RtpSelectedPayloadGate(val expectedPayloadType: Int?) {
    enum class Verdict { SELECTED_VIDEO, FOREIGN_PAYLOAD, MALFORMED }
    data class Metrics(
        val selected: Long,
        val foreignPayload: Long,
        val malformed: Long
    )
    private val selected = AtomicLong()
    private val foreign = AtomicLong()
    private val malformed = AtomicLong()

    init { require(expectedPayloadType == null || expectedPayloadType in 0..127) }

    fun classify(packet: ByteArray): Verdict {
        if (packet.size < 12 || (packet[0].toInt() and 0xc0) != 0x80) {
            malformed.incrementAndGet()
            return Verdict.MALFORMED
        }
        val csrcCount = packet[0].toInt() and 0x0f
        if (packet.size <= 12 + csrcCount * 4) {
            malformed.incrementAndGet()
            return Verdict.MALFORMED
        }
        val pt = packet[1].toInt() and 0x7f
        if (expectedPayloadType != null && pt != expectedPayloadType) {
            foreign.incrementAndGet()
            return Verdict.FOREIGN_PAYLOAD
        }
        selected.incrementAndGet()
        return Verdict.SELECTED_VIDEO
    }

    fun metrics(): Metrics = Metrics(
        selected.get(), foreign.get(), malformed.get()
    )
}
