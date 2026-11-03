package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class A500sOwnerProtocolTest {
    @Test fun pairingQueriesAreDeterministicAndDistinct() {
        val bind = A500sOwnerProtocol.bindQuery("1234567")
        val confirm = A500sOwnerProtocol.confirmQuery("1705513930")
        assertTrue(bind.startsWith("BindByBanya.cgi?&-usr=1234567&-signkey="))
        assertTrue(confirm.startsWith("UserconfirmByBanya.cgi?&-timestamp="))
        assertEquals(32, bind.substringAfter("-signkey=").length)
        assertEquals(32, confirm.substringAfter("-signkey=").length)
    }
    @Test fun registrationSignsRequestUsingAuthorizedToken() {
        val token = "0123456789abcdef0123456789abcdef"
        val signed = A500sOwnerProtocol.registerQuery("192.168.0.20",1705513930,token)
        assertTrue(signed.startsWith("client.cgi?&-operation=register&-ip=192.168.0.20"))
        assertEquals(A500sOwnerProtocol.md5(signed.substringBefore("&-signkey=")+token),
            signed.substringAfter("-signkey="))
    }
    @Test fun rejectsInvalidOrMissingAuthorization() {
        try {
            A500sOwnerProtocol.registerQuery("192.168.0.20",1705513930,"missing")
            fail("Unpaired registration must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
