package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class RtspWireReaderTest {
    private fun reply(cseq: Int = 4, body: String = ""): ByteArray =
        ("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Length: " +
            body.toByteArray().size + "\r\n\r\n" + body).toByteArray(Charsets.US_ASCII)

    private fun interleave(channel: Int, bytes: ByteArray): ByteArray =
        byteArrayOf(0x24, channel.toByte(),
            (bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes

    @Test fun plainRtspResponse() {
        val wire = RtspWireReader(ByteArrayInputStream(reply(3, "v=0\r\n")))
        val status = wire.awaitResponse(3)
        assertEquals(200, status.code)
        assertEquals("v=0\r\n", status.text)
    }

    @Test fun binaryAheadOfPlayResponseIsSkippedAtExactFramingBoundary() {
        val bytes = byteArrayOf(0x80.toByte(), 0x60, 0x24, 0x0A, 0x0D, 0xFF.toByte())
        val wire = RtspWireReader(ByteArrayInputStream(
            interleave(0, bytes) + interleave(1, byteArrayOf(0x11,0x12)) + reply(4)
        ))
        val channels = mutableListOf<Int>()
        val status = wire.awaitResponse(4) { channels += it.channel }
        assertEquals(listOf(0,1), channels)
        assertEquals(200, status.code)
    }

    @Test fun binaryPayloadWithNewlinesCannotCorruptRtspParser() {
        val payload = byteArrayOf(0x52,0x54,0x53,0x50,10,13,0x24,0,0,0,0)
        val stream = interleave(88, payload) + reply(1, "abc") +
            interleave(0,byteArrayOf(0x80.toByte(),0x60))
        val wire = RtspWireReader(ByteArrayInputStream(stream))
        val channels = mutableListOf<Int>()
        assertEquals("abc", wire.awaitResponse(1) { channels += it.channel }.text)
        assertEquals(listOf(88), channels)
        val media = wire.next() as RtspWireReader.Event.Interleaved
        assertEquals(0, media.channel)
        assertEquals(2, media.payload.size)
    }

    @Test fun wrongCseqDoesNotPass() {
        try {
            RtspWireReader(ByteArrayInputStream(reply(3))).awaitResponse(4)
            fail("Wrong CSeq must not be accepted")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("CSeq mismatch"))
        }
    }

    @Test fun unknownBinaryPrefixIsReportedWithoutBinaryDump() {
        try {
            RtspWireReader(ByteArrayInputStream(byteArrayOf(0x80.toByte(),0x10)))
                .awaitResponse(1)
            fail("Unknown framing must be rejected")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("0x80"))
            assertFalse(expected.message!!.contains("\uFFFD"))
        }
    }

    @Test fun truncatedInterleavedFrameFailsClosed() {
        try {
            val packet=byteArrayOf(0x24, 0, 0, 5, 0x01)
            RtspWireReader(ByteArrayInputStream(packet)).next()
            fail("Truncated packets must not reach H264 decoder")
        } catch (_: EOFException) { }
    }
}
