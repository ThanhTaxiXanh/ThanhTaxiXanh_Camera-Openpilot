package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class RtspSourcePolicyTest {
    @Test fun a500sAddressAndValidCustomCameraUrlPass() {
        assertEquals("192.168.0.1",
            RtspSourcePolicy.gateway("192.168.0.1"))
        assertEquals("10.0.0.1",RtspSourcePolicy.gateway("10.0.0.1"))
        assertEquals("172.16.1.2",RtspSourcePolicy.gateway("172.16.1.2"))
        assertEquals("rtsp://192.168.0.1:554/livestream/12",
            RtspSourcePolicy.check("  rtsp://192.168.0.1:554/livestream/12 "))
        assertEquals("rtsp://dashcam.local/stream1",
            RtspSourcePolicy.check("rtsp://dashcam.local/stream1"))
    }

    @Test fun invalidGatewayValuesAreRejectedBeforeCreatingNetworkEndpoint() {
        listOf("999.999.999.999","256.0.0.1","127.0.0.1",
            "0.0.0.0","169.254.1.2","224.0.0.1","192.168.00.1",
            "10.0.0","10.0.0.1:554","255.255.255.255").forEach { value ->
            reject { RtspSourcePolicy.gateway(value) }
        }
    }

    @Test fun urlRefusesCredentialsForeignSchemeFragmentsAndInvalidPorts() {
        listOf(
            "http://192.168.0.1/stream",
            "file:///etc/passwd",
            "rtsp://admin:secret@192.168.0.1:554/video",
            "rtsp://192.168.0.1/stream#fragment",
            "rtsp://192.168.0.1:0/stream",
            "rtsp://192.168.0.1:65536/stream",
            "rtsp://127.0.0.1/stream",
            "rtsp://localhost/stream",
            "rtsp://999.999.999.999/stream",
            "rtsp://192.168.0.1/stream\nmalicious",
            "rtsp://192.168.0.1/stream\rmalicious"
        ).forEach { value -> reject { RtspSourcePolicy.check(value) } }
    }

    private fun reject(block:()->Unit) {
        try {
            block()
            fail("Rejected camera source URL must not be accepted")
        } catch (_:IllegalArgumentException) {}
    }
}
