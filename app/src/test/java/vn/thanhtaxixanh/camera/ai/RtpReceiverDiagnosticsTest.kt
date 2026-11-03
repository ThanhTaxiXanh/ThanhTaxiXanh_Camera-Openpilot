package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class RtpReceiverDiagnosticsTest {
    private fun pkt(seq: Int, ts: Long, marker: Boolean = false): ByteArray {
        val p = ByteArray(14)
        p[0] = 0x80.toByte()
        p[1] = (96 + if (marker) 128 else 0).toByte()
        p[2] = (seq ushr 8).toByte()
        p[3] = seq.toByte()
        p[4] = (ts ushr 24).toByte()
        p[5] = (ts ushr 16).toByte()
        p[6] = (ts ushr 8).toByte()
        p[7] = ts.toByte()
        p[8] = 0x42
        p[9] = 0x43
        p[10] = 0x44
        p[11] = 0x45
        p[12] = 0x65
        p[13] = 0x20
        return p
    }
    @Test fun timestampsAndPacketMarkersAreSeparateCounters() {
        val stats = RtpReceiverDiagnostics()
        stats.observe(pkt(10, 90000))
        stats.observe(pkt(11, 90000, true))
        stats.observe(pkt(12, 93000, true))
        val s = stats.snapshot()
        assertEquals(3, s.packets)
        assertEquals(2, s.markers)
        assertEquals(1, s.timestampChanges)
        assertEquals(0, s.sequenceGaps)
        assertEquals(42, s.bytes)
    }
    @Test fun sequenceGapDetectedWithoutAssumingMissingAccessUnit() {
        val stats = RtpReceiverDiagnostics()
        stats.observe(pkt(65534, 120000))
        stats.observe(pkt(0, 123000))
        assertEquals(1, stats.snapshot().sequenceGaps)
        assertEquals(1, stats.snapshot().lostPackets)
    }
    @Test fun compoundRrIncludesReportAndSdes() {
        val stats = RtpReceiverDiagnostics()
        stats.observe(pkt(10, 90000))
        val report = RtcpReceiverReport.compound(0x12345678, stats.snapshot())
        assertEquals(0x81, report[0].toInt() and 255)
        assertEquals(201, report[1].toInt() and 255)
        assertEquals(7, report[3].toInt() and 255)
        assertEquals(0x81, report[32].toInt() and 255)
        assertEquals(202, report[33].toInt() and 255)
        assertEquals(0, report.size % 4)
    }
    @Test fun rejectsMalformedInput() {
        val stats = RtpReceiverDiagnostics()
        assertFalse(stats.observe(byteArrayOf(0, 1, 2)))
        assertEquals(1, stats.snapshot().invalid)
    }
}