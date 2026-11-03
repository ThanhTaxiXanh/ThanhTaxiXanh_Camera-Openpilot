package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class RtspCandidateDiscoveryTest {
    @Test fun onlyKnownCameraRoutesAreProbed() {
        assertTrue(RtspCandidateDiscovery.paths.contains("/livestream/12"))
        assertTrue(RtspCandidateDiscovery.paths.contains("/liveRTSP/av4"))
        assertEquals(RtspCandidateDiscovery.paths.distinct().size,
            RtspCandidateDiscovery.paths.size)
        assertTrue(RtspCandidateDiscovery.paths.all { it.startsWith("/") })
    }
}
