package vn.thanhtaxixanh.camera.ai

import java.io.ByteArrayOutputStream

/**
 * RTP/H264 single-NAL, STAP-A and FU-A depacketizer (RFC 6184).
 *
 * One network receive thread owns accept(). Do not forward damaged Annex-B
 * access units into MediaCodec: after damage we wait for a complete IDR.
 * Conservatively quarantine BOTH sides of a sequence gap at a timestamp
 * transition because the missing RTP packet could belong to either AU.
 * This is independent of the optional YUV/ONNX sidecar and does not alter
 * hardware decoder, display Surface, camera protocol, or network pacing.
 */
class RtpH264AccessUnitAssembler(
    private val emit: (ByteArray, Long) -> Unit,
    private val onDamaged: () -> Unit,
    private val onPacket: (ByteArray) -> Unit = {},
    private val onLostSequence: () -> Unit = {},
    private val onDiscarded: () -> Unit = {}
) {
    companion object { const val MAX_ACCESS_UNIT_BYTES = 1_500_000 }

    private val output = ByteArrayOutputStream(65536)
    private val startCode = byteArrayOf(0, 0, 0, 1)
    private var timestamp: Long? = null
    private var lastSequence: Int? = null
    private var broken = false
    private var fragmentOpen = false
    private var fragmentNalId: Int? = null
    private var awaitingIdr = true
    // SSRC and dynamic payload type jointly define the RTP stream epoch.
    // Changing either must never concatenate H264 from different sources.
    private var sourceSsrc: Long? = null
    private var sourcePayloadType: Int? = null

    /** Pure source eligibility, not a decoder success indication. */
    fun needsIdr(): Boolean = awaitingIdr

    private fun appendNalHeaderAndBytes(data: ByteArray, start: Int, length: Int) {
        if (broken) return
        if (length <= 0 || output.size().toLong() + 4L + length >
            MAX_ACCESS_UNIT_BYTES.toLong()) {
            broken = true
            return
        }
        output.write(startCode)
        output.write(data, start, length)
    }

    private fun appendFragment(data: ByteArray, start: Int, length: Int) {
        if (broken) return
        if (length <= 0 || output.size().toLong() + length >
            MAX_ACCESS_UNIT_BYTES.toLong()) {
            broken = true
            return
        }
        output.write(data, start, length)
    }

    private fun finishAccessUnit() {
        if (timestamp != null) {
            if (broken || fragmentOpen) {
                awaitingIdr = true
                onDiscarded()
                onDamaged()
            } else if (output.size() > 0) {
                val bytes = output.toByteArray()
                if (!awaitingIdr || H264AnnexB.hasIdr(bytes)) {
                    awaitingIdr = false
                    emit(bytes, timestamp!!)
                }
            }
        }
        output.reset()
        timestamp = null
        broken = false
        fragmentOpen = false
        fragmentNalId = null
    }

    private fun invalidPacket() {
        if (timestamp != null) {
            broken = true
            finishAccessUnit()
        } else {
            awaitingIdr = true
            onDamaged()
        }
    }

    fun accept(packet: ByteArray) {
        onPacket(packet)
        if (packet.size < 13 || (packet[0].toInt() and 0xc0) != 0x80) {
            invalidPacket()
            return
        }
        val cc = packet[0].toInt() and 0x0f
        val extension = (packet[0].toInt() and 0x10) != 0
        val padding = (packet[0].toInt() and 0x20) != 0
        val headerLength = 12 + 4 * cc
        if (headerLength >= packet.size) {
            invalidPacket()
            return
        }
        var offset = headerLength
        if (extension) {
            if (offset + 4 > packet.size) {
                invalidPacket()
                return
            }
            val words = ((packet[offset + 2].toInt() and 255) shl 8) or
                (packet[offset + 3].toInt() and 255)
            // Use Long for the calculation even for hostile extension lengths.
            val next = offset.toLong() + 4L + words.toLong() * 4L
            if (next >= packet.size) {
                invalidPacket()
                return
            }
            offset = next.toInt()
        }
        val payloadEnd = if (padding) {
            val count = packet.last().toInt() and 255
            if (count <= 0 || count > packet.size - offset) {
                invalidPacket()
                return
            }
            packet.size - count
        } else packet.size
        if (offset >= payloadEnd) {
            invalidPacket()
            return
        }

        val source = RtpReceiverDiagnostics.unsigned32(packet, 8)
        val payloadType = packet[1].toInt() and 0x7f
        if (sourceSsrc != null &&
            (sourceSsrc != source || sourcePayloadType != payloadType)) {
            // The old AU can no longer be trusted, even if it had no
            // missing sequence number or its timestamp coincides.
            if (timestamp != null) {
                broken = true
                finishAccessUnit()
            } else {
                awaitingIdr = true
                onDamaged()
            }
            lastSequence = null // New RTP sequence domain.
        }
        sourceSsrc = source
        sourcePayloadType = payloadType
        val sequence = ((packet[2].toInt() and 255) shl 8) or
            (packet[3].toInt() and 255)
        val clock = ((packet[4].toLong() and 255L) shl 24) or
            ((packet[5].toLong() and 255L) shl 16) or
            ((packet[6].toLong() and 255L) shl 8) or
            (packet[7].toLong() and 255L)
        val gap = lastSequence?.let { ((it + 1) and 65535) != sequence } ?: false
        if (gap) onLostSequence()
        if (timestamp != null && timestamp != clock) {
            // A lost packet at this boundary might be the final packet of
            // the previous AU. Never emit that possibly truncated picture.
            if (gap) broken = true
            finishAccessUnit()
        }
        if (timestamp == null) timestamp = clock
        if (gap) broken = true
        lastSequence = sequence
        val marker = (packet[1].toInt() and 0x80) != 0

        // H264 forbidden_zero_bit must be zero in NAL headers.
        if ((packet[offset].toInt() and 0x80) != 0) broken = true
        when (packet[offset].toInt() and 0x1f) {
            in 1..23 -> {
                if (fragmentOpen) broken = true
                appendNalHeaderAndBytes(packet, offset, payloadEnd - offset)
            }
            24 -> {
                if (fragmentOpen) broken = true
                var at = offset + 1
                val nalRanges = ArrayList<Pair<Int, Int>>()
                while (at < payloadEnd && !broken) {
                    if (at + 2 > payloadEnd) {
                        broken = true
                        break
                    }
                    val size = ((packet[at].toInt() and 255) shl 8) or
                        (packet[at + 1].toInt() and 255)
                    at += 2
                    if (size < 1 || size > payloadEnd - at ||
                        (packet[at].toInt() and 0x80) != 0 ||
                        (packet[at].toInt() and 0x1f) !in 1..23) {
                        broken = true
                        break
                    }
                    nalRanges.add(at to size)
                    at += size
                }
                if (nalRanges.isEmpty()) broken = true
                if (!broken) nalRanges.forEach { (start, size) ->
                    appendNalHeaderAndBytes(packet, start, size)
                }
            }
            28 -> {
                if (payloadEnd - offset < 3) {
                    broken = true
                } else {
                    val fu = packet[offset + 1].toInt() and 255
                    val start = (fu and 0x80) != 0
                    val end = (fu and 0x40) != 0
                    val nalType = fu and 0x1f
                    val nalId = (packet[offset].toInt() and 0xe0) or nalType
                    if ((fu and 0x20) != 0 || (start && end) ||
                        nalType !in 1..23) {
                        broken = true
                    } else if (start) {
                        if (fragmentOpen) {
                            broken = true
                        } else {
                            val nalHeader = ((packet[offset].toInt() and 0xe0) or
                                nalType).toByte()
                            appendNalHeaderAndBytes(byteArrayOf(nalHeader), 0, 1)
                            appendFragment(packet, offset + 2, payloadEnd - offset - 2)
                            if (!broken) {
                                fragmentOpen = true
                                fragmentNalId = nalId
                            }
                        }
                    } else {
                        if (!fragmentOpen || fragmentNalId != nalId) {
                            // Do not concatenate different FU-A NAL types or
                            // incompatible NRI bits into one fabricated NAL.
                            broken = true
                        } else {
                            appendFragment(packet, offset + 2, payloadEnd - offset - 2)
                            if (end) {
                                fragmentOpen = false
                                fragmentNalId = null
                            }
                        }
                    }
                }
            }
            else -> broken = true
        }
        if (marker) finishAccessUnit()
    }
}
