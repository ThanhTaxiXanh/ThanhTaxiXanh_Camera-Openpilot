package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class IjkPlaybackProfileTest {
    @Test fun defaultsRemainBufferedToPreserveSmoothness() {
        assertEquals(1L, IjkPlaybackProfile.SMOOTH.packetBuffering)
        assertEquals(0L, IjkPlaybackProfile.LOW_LATENCY.packetBuffering)
    }
    @Test fun profileToggleIsReversible() {
        assertSame(IjkPlaybackProfile.LOW_LATENCY, IjkPlaybackProfile.SMOOTH.next())
        assertSame(IjkPlaybackProfile.SMOOTH, IjkPlaybackProfile.LOW_LATENCY.next())
    }
}
