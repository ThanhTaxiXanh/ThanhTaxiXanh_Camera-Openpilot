package vn.thanhtaxixanh.camera.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Diagnostic RTP source counters, independently of the H264 access-unit
 * depacketizer and decoder. One network receiver calls observe().
 *
 * RTCP receiver report creation follows RFC 3550, with compound RR+SDES CNAME.
 * Counters distinguish network packet rate, camera RTP timestamp changes,
 * markers, and successfully assembled H264 access units.
 */
class RtpReceiverDiagnostics {
    data class Snapshot(
        val packets: Long,
        val bytes: Long,
        val markers: Long,
        val timestampChanges: Long,
        val sequenceGaps: Long,
        val reorderedOrDuplicates: Long,
        val invalid: Long,
        val sourceSsrc: Long,
        val highestExtendedSeq: Long,
        val jitterTicks: Long,
        val lostPackets: Long,
        // RFC 3550 reception report counters are scoped to one SSRC epoch.
        // The legacy nonnegative lostPackets remains a UI diagnostic only.
        val sourceEpoch: Long = 0L,
        val hasSource: Boolean = false,
        val expectedPackets: Long = 0L,
        val receivedPackets: Long = 0L,
        val signedLostPackets: Long = 0L
    )

    private var packets = 0L
    private var bytes = 0L
    private var markers = 0L
    private var timestampChanges = 0L
    private var sequenceGaps = 0L
    private var duplicateOrOld = 0L
    private var invalid = 0L
    private var sourceSsrc = 0L
    private var hasSource = false
    private var sourceEpoch = 0L
    private var baseSeq = -1L
    private var highestSeq = -1L
    private var lastRtpTimestamp = -1L
    private var previousTransitNs = 0L
    private var previousTransitTimestamp = -1L
    private var jitter = 0.0
    // RFC 3550 counts valid packets received, including late/duplicates.
    // A previous unique-highest counter falsely made recovered packets lost.
    private var sourceReceived = 0L

    @Synchronized
    fun observe(p: ByteArray): Boolean {
        if (p.size < 12 || (p[0].toInt() and 0xC0) != 0x80) {
            invalid++
            return false
        }
        val sequence = (((p[2].toInt() and 255) shl 8) or
            (p[3].toInt() and 255)).toLong()
        val timestamp = unsigned32(p, 4)
        val ssrc = unsigned32(p, 8)
        if (!hasSource || ssrc != sourceSsrc) {
            // SSRC=0 is a valid identifier, not an uninitialized sentinel.
            // Reset BEFORE accounting for the first packet of each epoch.
            hasSource = true
            sourceSsrc = ssrc
            sourceEpoch++
            baseSeq = -1L
            highestSeq = -1L
            sourceReceived = 0L
            previousTransitNs = 0L
            previousTransitTimestamp = -1L
            lastRtpTimestamp = -1L
            jitter = 0.0
        }

        packets++
        bytes += p.size.toLong()
        if ((p[1].toInt() and 0x80) != 0) markers++
        if (timestamp != lastRtpTimestamp) {
            if (lastRtpTimestamp != -1L) timestampChanges++
            lastRtpTimestamp = timestamp
        }

        sourceReceived++
        if (highestSeq < 0L) {
            highestSeq = sequence
            baseSeq = sequence
        } else {
            val prior = highestSeq and 65535L
            val step = (sequence - prior + 65536L) and 65535L
            if (step == 0L || step >= 32768L) {
                duplicateOrOld++
            } else {
                if (step > 1L) sequenceGaps += step - 1L
                highestSeq += step
            }
        }

        val arrivalNs = System.nanoTime()
        if (previousTransitTimestamp >= 0L) {
            val deltaTimestamp = signedRtpDelta(timestamp, previousTransitTimestamp)
            val deltaArrivalTicks =
                (arrivalNs - previousTransitNs).toDouble() * 90000.0 / 1e9
            val delta = abs(deltaArrivalTicks - deltaTimestamp.toDouble())
            jitter += (delta - jitter) / 16.0
        }
        previousTransitNs = arrivalNs
        previousTransitTimestamp = timestamp
        return true
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val expected = if (baseSeq >= 0L) highestSeq - baseSeq + 1L else 0L
        val signedLost = expected - sourceReceived
        return Snapshot(packets, bytes, markers, timestampChanges, sequenceGaps,
            duplicateOrOld, invalid, sourceSsrc, highestSeq.coerceAtLeast(0L),
            jitter.coerceAtLeast(0.0).toLong(),
            signedLost.coerceAtLeast(0L),
            sourceEpoch, hasSource, expected, sourceReceived, signedLost)
    }

    companion object {
        fun unsigned32(b: ByteArray, i: Int) =
            ((b[i].toLong() and 255) shl 24) or
                ((b[i+1].toLong() and 255) shl 16) or
                ((b[i+2].toLong() and 255) shl 8) or
                (b[i+3].toLong() and 255)

        fun signedRtpDelta(current: Long, previous: Long): Long {
            val raw = (current - previous) and 0xffffffffL
            return if (raw >= 0x80000000L) raw - 0x100000000L else raw
        }
    }
}

/** Generates RTCP compound RR+SDES; sends no device identifying information. */
object RtcpReceiverReport {
    fun compound(
        reporterSsrc: Int,
        source: RtpReceiverDiagnostics.Snapshot,
        cname: String = "ttx-camera-ai",
        fractionLost: Int = 0
    ): ByteArray {
        require(cname.isNotEmpty() && cname.length <= 64 && cname.all { it.code in 33..126 })
        require(fractionLost in 0..255)
        if (!source.hasSource) return ByteArray(0)
        val rr = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        rr.put(0x81.toByte())   // V=2, one report block
        rr.put(201.toByte())    // RTCP RR
        rr.putShort(7)         // 32 bytes /4 - 1
        rr.putInt(reporterSsrc)
        rr.putInt(source.sourceSsrc.toInt())
        // RR report block: unsigned 8-bit fraction + SIGNED 24-bit loss.
        // Avoid converting negative loss to a false zero or overflowing 24 bits.
        val loss24 = source.signedLostPackets.coerceIn(-0x800000L, 0x7fffffL).toInt()
        rr.putInt((fractionLost shl 24) or (loss24 and 0xffffff))
        rr.putInt(source.highestExtendedSeq.toInt())
        rr.putInt(source.jitterTicks.toInt())
        rr.putInt(0)           // LSR unknown; do not claim sender report
        rr.putInt(0)           // DLSR unknown
        val cnameBytes = cname.toByteArray(Charsets.US_ASCII)
        val unpadded = 4 + 4 + 2 + cnameBytes.size + 1
        val sdesLength = ((unpadded + 3) / 4) * 4
        val sdes = ByteBuffer.allocate(sdesLength).order(ByteOrder.BIG_ENDIAN)
        sdes.put(0x81.toByte())
        sdes.put(202.toByte())
        sdes.putShort((sdesLength / 4 - 1).toShort())
        sdes.putInt(reporterSsrc)
        sdes.put(1.toByte()) // SDES CNAME
        sdes.put(cnameBytes.size.toByte())
        sdes.put(cnameBytes)
        sdes.put(0.toByte()) // END, pad with zeros
        return rr.array() + sdes.array()
    }

    /**
     * RFC 3550 fraction lost covers the interval SINCE THE LAST SENT RR.
     * Preparing a report does not advance the baseline: UDP/TCP sends may
     * fail, so call markSent only after the RTCP write completes.
     * Source changes reset the interval even when the new SSRC is zero.
     */
    class Tracker {
        data class Prepared(
            val bytes: ByteArray,
            val epoch: Long,
            val expectedPackets: Long,
            val receivedPackets: Long
        )
        private var previousEpoch = -1L
        private var previousExpected = 0L
        private var previousReceived = 0L

        fun prepare(reporterSsrc: Int, source: RtpReceiverDiagnostics.Snapshot): Prepared? {
            if (!source.hasSource) return null
            val sameEpoch = previousEpoch == source.sourceEpoch
            val expectedDelta = source.expectedPackets -
                (if (sameEpoch) previousExpected else 0L)
            val receivedDelta = source.receivedPackets -
                (if (sameEpoch) previousReceived else 0L)
            val lostDelta = expectedDelta - receivedDelta
            val fraction = if (expectedDelta <= 0L || lostDelta <= 0L) 0 else
                ((lostDelta * 256L) / expectedDelta).coerceIn(0L,255L).toInt()
            return Prepared(compound(reporterSsrc,source,fractionLost=fraction),
                source.sourceEpoch,source.expectedPackets,source.receivedPackets)
        }

        fun markSent(report: Prepared) {
            previousEpoch = report.epoch
            previousExpected = report.expectedPackets
            previousReceived = report.receivedPackets
        }
    }
}
