package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class RtspTransportNegotiationTest {
    @Test fun udpFromA500sStyleSetup() {
        assertTrue(RtspTransportNegotiation.isUdpTransport(
            "RTP/AVP;unicast;client_port=34560-34561;server_port=5500-5501"))
    }
    @Test fun tcpFallbackIsNeverMisidentifiedAsUdp() {
        val tcp = "RTP/AVP/TCP;unicast;interleaved=0-1"
        assertFalse(RtspTransportNegotiation.isUdpTransport(tcp))
        assertTrue(RtspTransportNegotiation.isTcpTransport(tcp))
    }
    @Test fun emptyAndMalformedAreNotConsideredValid() {
        assertFalse(RtspTransportNegotiation.isUdpTransport(""))
        assertFalse(RtspTransportNegotiation.isTcpTransport("RTP/AVP;unicast"))
    }
}
