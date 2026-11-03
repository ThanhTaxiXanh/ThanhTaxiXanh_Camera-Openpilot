package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class IjkPlaybackProfileTest {
    @Test fun smoothTcpPreserved() {
        assertEquals("tcp", IjkPlaybackProfile.SMOOTH.rtspTransport)
        assertEquals(1L, IjkPlaybackProfile.SMOOTH.packetBuffering)
        assertFalse(IjkPlaybackProfile.SMOOTH.experimentalLowLatency)
    }
    @Test fun lowLatencyTcpPreserved() {
        assertEquals("tcp", IjkPlaybackProfile.LOW_LATENCY.rtspTransport)
        assertEquals(0L, IjkPlaybackProfile.LOW_LATENCY.packetBuffering)
        assertTrue(IjkPlaybackProfile.LOW_LATENCY.experimentalLowLatency)
    }
    @Test fun udpOnlyChangesTransport() {
        val a = IjkPlaybackProfile.SMOOTH
        val b = IjkPlaybackProfile.UDP_SMOOTH
        assertEquals("udp", b.rtspTransport)
        assertEquals(a.packetBuffering, b.packetBuffering)
        assertEquals(a.experimentalLowLatency, b.experimentalLowLatency)
    }
    @Test fun cyclesThroughThreeProfiles() {
        assertSame(IjkPlaybackProfile.LOW_LATENCY, IjkPlaybackProfile.SMOOTH.next())
        assertSame(IjkPlaybackProfile.UDP_SMOOTH, IjkPlaybackProfile.LOW_LATENCY.next())
        assertSame(IjkPlaybackProfile.SMOOTH, IjkPlaybackProfile.UDP_SMOOTH.next())
    }
}
