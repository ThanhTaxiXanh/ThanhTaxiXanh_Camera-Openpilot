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
    @Test fun balancedTcpCapsCacheWithoutDisablingPacketBuffering() {
        val s = IjkPlaybackProfile.SMOOTH
        val b = IjkPlaybackProfile.BALANCED
        assertEquals(s.rtspTransport, b.rtspTransport)
        assertEquals(s.packetBuffering, b.packetBuffering)
        assertEquals(10L, b.minFrames)
        assertEquals(1000L, b.maxCachedDurationMs)
        assertEquals(500_000L, b.maxDelayUs)
        assertFalse(b.experimentalLowLatency)
    }
    @Test fun nativeSmoothAndUdpDefaultsAreUnchanged() {
        for (p in listOf(IjkPlaybackProfile.SMOOTH, IjkPlaybackProfile.UDP_SMOOTH)) {
            assertNull(p.minFrames)
            assertNull(p.maxCachedDurationMs)
            assertNull(p.maxDelayUs)
            assertEquals(1L, p.packetBuffering)
        }
    }
    @Test fun lowLatencyHasDistinctAggressiveBoundsAndPacketBufferingOff() {
        val b = IjkPlaybackProfile.BALANCED
        val l = IjkPlaybackProfile.LOW_LATENCY
        assertEquals(0L, l.packetBuffering)
        assertEquals(5L, l.minFrames)
        assertEquals(600L, l.maxCachedDurationMs)
        assertEquals(300_000L, l.maxDelayUs)
        assertTrue(b.maxCachedDurationMs!! > l.maxCachedDurationMs!!)
    }
    @Test fun cyclesThroughFourProfiles() {
        assertSame(IjkPlaybackProfile.BALANCED, IjkPlaybackProfile.SMOOTH.next())
        assertSame(IjkPlaybackProfile.LOW_LATENCY, IjkPlaybackProfile.BALANCED.next())
        assertSame(IjkPlaybackProfile.UDP_SMOOTH, IjkPlaybackProfile.LOW_LATENCY.next())
        assertSame(IjkPlaybackProfile.SMOOTH, IjkPlaybackProfile.UDP_SMOOTH.next())
    }
}
