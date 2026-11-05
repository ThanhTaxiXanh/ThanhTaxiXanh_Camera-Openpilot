package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * RFC 3550 receiver-report regression: synthetic RTP bytes only.
 * Neither A500S playback nor independent optical/AI results are exercised.
 */
class RtcpReceiverReportEpochTest {
    private fun packet(seq: Int, ssrc: Long = 0x11223344L): ByteArray {
        val p = ByteArray(14)
        p[0] = 0x80.toByte()
        p[1] = 0xe0.toByte() // marker, H264 dynamic payload type 96
        p[2] = (seq ushr 8).toByte()
        p[3] = seq.toByte()
        val timestamp = seq.toLong() * 3000L
        for (i in 0..3) {
            p[4+i] = (timestamp ushr (24-8*i)).toByte()
            p[8+i] = (ssrc ushr (24-8*i)).toByte()
        }
        p[12] = 0x65
        p[13] = 0x41
        return p
    }

    private fun field32(b: ByteArray, index: Int): Int =
        ((b[index].toInt() and 255) shl 24) or
            ((b[index+1].toInt() and 255) shl 16) or
            ((b[index+2].toInt() and 255) shl 8) or
            (b[index+3].toInt() and 255)

    @Test fun firstPacketOfReplacementSsrcIsCountedOnceNotLost() {
        val d = RtpReceiverDiagnostics()
        d.observe(packet(500,ssrc=11))
        d.observe(packet(501,ssrc=11))
        val before = d.snapshot()
        assertEquals(1L,before.sourceEpoch)
        d.observe(packet(25,ssrc=22))
        val snap = d.snapshot()
        assertEquals(2L,snap.sourceEpoch)
        assertEquals(22L,snap.sourceSsrc)
        assertEquals(25L,snap.highestExtendedSeq)
        assertEquals(1L,snap.expectedPackets)
        assertEquals(1L,snap.receivedPackets)
        assertEquals(0L,snap.signedLostPackets)
        assertEquals(0L,snap.lostPackets)
        assertEquals(before.reorderedOrDuplicates,snap.reorderedOrDuplicates)
    }

    @Test fun sourceSsrcZeroIsValidAndSwitchesEpochCleanly() {
        val d = RtpReceiverDiagnostics()
        assertEquals(0,RtcpReceiverReport.compound(7,d.snapshot()).size)
        d.observe(packet(65535,ssrc=0))
        var snap = d.snapshot()
        assertTrue(snap.hasSource)
        assertEquals(0L,snap.sourceSsrc)
        assertEquals(1L,snap.expectedPackets)
        assertTrue(RtcpReceiverReport.compound(7,snap).isNotEmpty())
        d.observe(packet(0,ssrc=0))
        assertEquals(65536L,d.snapshot().highestExtendedSeq)
        d.observe(packet(30,ssrc=1))
        d.observe(packet(20,ssrc=0))
        snap = d.snapshot()
        assertEquals(3L,snap.sourceEpoch)
        assertEquals(1L,snap.expectedPackets)
        assertEquals(1L,snap.receivedPackets)
        assertEquals(0L,snap.lostPackets)
    }

    @Test fun recoveredOutOfOrderPacketDecrementsCumulativeLoss() {
        val d = RtpReceiverDiagnostics()
        d.observe(packet(10))
        d.observe(packet(12)) // 11 is initially missing
        assertEquals(1L,d.snapshot().signedLostPackets)
        d.observe(packet(11)) // late arrival: RFC receipt count includes it
        val snap = d.snapshot()
        assertEquals(3L,snap.expectedPackets)
        assertEquals(3L,snap.receivedPackets)
        assertEquals(0L,snap.signedLostPackets)
        assertEquals(1L,snap.reorderedOrDuplicates)
    }

    @Test fun duplicatePacketProducesSignedNegativeLossOnWire() {
        val d = RtpReceiverDiagnostics()
        d.observe(packet(200))
        d.observe(packet(200))
        val snap = d.snapshot()
        assertEquals(-1L,snap.signedLostPackets)
        assertEquals(0L,snap.lostPackets) // UI remains non-negative
        val rr = RtcpReceiverReport.compound(0x1234,snap)
        assertEquals(0x81,rr[0].toInt() and 255)
        assertEquals(201,rr[1].toInt() and 255)
        assertEquals(0xffffff,field32(rr,12) and 0xffffff)
        assertEquals(0,field32(rr,12) ushr 24)
    }

    @Test fun intervalFractionUsesOnlyPacketsSinceLastSentReport() {
        val d = RtpReceiverDiagnostics()
        val tracker = RtcpReceiverReport.Tracker()
        d.observe(packet(10))
        val first = tracker.prepare(44,d.snapshot())!!
        assertEquals(0,field32(first.bytes,12) ushr 24)
        tracker.markSent(first)
        d.observe(packet(12))
        d.observe(packet(13)) // #11 missing: 1/3 of this interval
        val second = tracker.prepare(44,d.snapshot())!!
        assertEquals(85,field32(second.bytes,12) ushr 24)
        assertEquals(1,field32(second.bytes,12) and 0xffffff)
        tracker.markSent(second)
        d.observe(packet(14))
        d.observe(packet(15))
        val third = tracker.prepare(44,d.snapshot())!!
        assertEquals(0,field32(third.bytes,12) ushr 24)
        assertEquals(1,field32(third.bytes,12) and 0xffffff)
    }

    @Test fun failedSendMustNotAdvancePriorIntervalCounters() {
        val d = RtpReceiverDiagnostics()
        val tracker = RtcpReceiverReport.Tracker()
        d.observe(packet(20))
        val first = tracker.prepare(20,d.snapshot())!!
        tracker.markSent(first)
        d.observe(packet(22))
        val attempt = tracker.prepare(20,d.snapshot())!!
        assertEquals(128,field32(attempt.bytes,12) ushr 24)
        // Deliberately no markSent: transport write has NOT completed.
        val retry = tracker.prepare(20,d.snapshot())!!
        assertArrayEquals(attempt.bytes,retry.bytes)
        d.observe(packet(23))
        val withNext = tracker.prepare(20,d.snapshot())!!
        assertEquals(85,field32(withNext.bytes,12) ushr 24)
    }

    @Test fun ssRcEpochChangeClearsOldIntervalBaseline() {
        val d = RtpReceiverDiagnostics()
        val tracker = RtcpReceiverReport.Tracker()
        d.observe(packet(100,ssrc=333))
        d.observe(packet(104,ssrc=333))
        tracker.markSent(tracker.prepare(9,d.snapshot())!!)
        d.observe(packet(7,ssrc=444))
        val next = tracker.prepare(9,d.snapshot())!!
        assertEquals(0,field32(next.bytes,12) ushr 24)
        assertEquals(0,field32(next.bytes,12) and 0xffffff)
        assertEquals(444,field32(next.bytes,8))
    }

    @Test fun signed24BitLossIsClampedNotWrapped() {
        val d = RtpReceiverDiagnostics()
        d.observe(packet(1))
        val base = d.snapshot()
        val max=RtcpReceiverReport.compound(1,
            base.copy(signedLostPackets=9_000_000L))
        val min=RtcpReceiverReport.compound(1,
            base.copy(signedLostPackets=-9_000_000L))
        assertEquals(0x7fffff,field32(max,12) and 0xffffff)
        assertEquals(0x800000,field32(min,12) and 0xffffff)
        assertEquals(0,max.size % 4)
        assertEquals(0,min.size % 4)
    }

    @Test fun rtcpHeaderRejectsOutOfRangeLossFraction() {
        val d = RtpReceiverDiagnostics()
        d.observe(packet(1))
        for (invalid in listOf(-1,256)) {
            try {
                RtcpReceiverReport.compound(1,d.snapshot(),fractionLost=invalid)
                fail("Accepted invalid fraction: " + invalid)
            } catch (_: IllegalArgumentException) { }
        }
    }
}
