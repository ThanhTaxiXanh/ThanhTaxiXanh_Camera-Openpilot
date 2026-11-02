package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class RtspSessionMaintenanceTest {
    @Test fun readsOptionalSessionTimeout() {
        assertEquals(60, RtspSessionMaintenance.timeoutSeconds("00000039"))
        assertEquals(30, RtspSessionMaintenance.timeoutSeconds("00000039;timeout=30"))
        assertEquals(45, RtspSessionMaintenance.timeoutSeconds("abc; timeout = 45"))
        assertEquals(10, RtspSessionMaintenance.timeoutSeconds("abc;timeout=1"))
    }

    @Test fun limitsKeepaliveInterval() {
        assertEquals(5000L, RtspSessionMaintenance.intervalMs(10))
        assertEquals(10000L, RtspSessionMaintenance.intervalMs(30))
        assertEquals(15000L, RtspSessionMaintenance.intervalMs(60))
    }

    @Test fun requestIncludesSessionAndSequence() {
        val r = String(RtspSessionMaintenance.request(
            "GET_PARAMETER", "rtsp://192.168.0.1/00000039/",
            "ID123", 4
        ), Charsets.US_ASCII)
        assertTrue(r.startsWith("GET_PARAMETER rtsp://192.168.0.1/00000039/ RTSP/1.0\r\n"))
        assertTrue(r.contains("CSeq: 4\r\n"))
        assertTrue(r.contains("Session: ID123\r\n"))
        assertTrue(r.endsWith("Content-Length: 0\r\n\r\n"))
    }

    @Test fun rejectsHeaderInjection() {
        try {
            RtspSessionMaintenance.request("GET_PARAMETER",
                "rtsp://192.168.0.1/test", "xyz\r\nBAD: 1", 5)
            fail("Session ID must reject line breaks")
        } catch (_: IllegalArgumentException) { }
    }
}
