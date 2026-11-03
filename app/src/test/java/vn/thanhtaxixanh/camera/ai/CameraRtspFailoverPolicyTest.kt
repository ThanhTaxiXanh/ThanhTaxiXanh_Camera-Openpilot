package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class CameraRtspFailoverPolicyTest {
    @Test fun udpPlay200ButNoPacketsFallsBackToTcp() {
        assertTrue(CameraRtspFailoverPolicy.shouldTryTcp(
            true, "RTP/UDP",
            "IllegalStateException: RTP timeout: PLAY 200 but zero video packets for 5 seconds"))
    }
    @Test fun udpPacketsWithoutCompleteH264FallsBackToTcp() {
        assertTrue(CameraRtspFailoverPolicy.shouldTryTcp(
            true, "RTP/UDP",
            "RTP/H264 stalled: packets received but zero H264 access units"))
    }
    @Test fun keepaliveFailureAloneDoesNotSwitchMediaTransport() {
        assertFalse(CameraRtspFailoverPolicy.shouldTryTcp(
            true, "RTP/UDP", "GET_PARAMETER returned 405"))
    }
    @Test fun successfulTcpNegotiationDoesNotFallBackAgain() {
        assertFalse(CameraRtspFailoverPolicy.shouldTryTcp(
            true, "RTP/TCP", "zero video packets"))
        assertFalse(CameraRtspFailoverPolicy.shouldTryTcp(
            false, "RTP/UDP", "zero video packets"))
    }
}
