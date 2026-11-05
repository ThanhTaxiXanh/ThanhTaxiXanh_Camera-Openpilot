package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** Synthetic RTP only; not real A500S, codec, ONNX or optical latency proof. */
class RtpSelectedPayloadGateTest {
    private fun packet(
        seq: Int, timestamp: Long = 1000L, pt: Int = 96,
        ssrc: Long = 1L, nal: Int = 0x65
    ): ByteArray {
        val p = ByteArray(14)
        p[0] = 0x80.toByte()
        p[1] = (0x80 or pt).toByte()
        p[2] = (seq ushr 8).toByte()
        p[3] = seq.toByte()
        repeat(4) { i ->
            p[4+i] = (timestamp ushr (24-8*i)).toByte()
            p[8+i] = (ssrc ushr (24-8*i)).toByte()
        }
        p[12] = nal.toByte()
        p[13] = 0x41
        return p
    }

    @Test fun selectedPayloadAllowsIdrAndDependentPictures() {
        val gate = RtpSelectedPayloadGate(96)
        assertEquals(RtpSelectedPayloadGate.Verdict.SELECTED_VIDEO,
            gate.classify(packet(1)))
        assertEquals(RtpSelectedPayloadGate.Verdict.SELECTED_VIDEO,
            gate.classify(packet(2,2000,nal=0x41)))
        assertEquals(2L,gate.metrics().selected)
        assertEquals(0L,gate.metrics().foreignPayload)
    }

    @Test fun foreignPayloadIsDiscardedWithoutPoisoningUdpAssembler() {
        val gate = RtpSelectedPayloadGate(96)
        val queue = RtpJitterQueue()
        val emitted = mutableListOf<Long>()
        var damage = 0
        val assembler = RtpH264AccessUnitAssembler(
            emit = { _, ts -> emitted.add(ts) },
            onDamaged = { damage++ }
        )
        fun accept(bytes: ByteArray, clock: Long) {
            if (gate.classify(bytes) !=
                RtpSelectedPayloadGate.Verdict.FOREIGN_PAYLOAD)
                queue.accept(bytes,clock) { assembler.accept(it) }
        }
        accept(packet(10,1000,pt=96,ssrc=1,nal=0x65),0)
        accept(packet(700,5000,pt=97,ssrc=2,nal=0x65),1)
        accept(packet(701,6000,pt=97,ssrc=2,nal=0x41),2)
        accept(packet(11,2000,pt=96,ssrc=1,nal=0x41),3)
        assertEquals(listOf(1000L,2000L), emitted)
        assertEquals(0,damage)
        assertEquals(0L,queue.sourceEpochResets)
        assertEquals(2L,gate.metrics().foreignPayload)
        assertEquals(2L,gate.metrics().selected)
    }

    @Test fun foreignPayloadDoesNotSuppressActualSelectedRtpLoss() {
        val gate = RtpSelectedPayloadGate(96)
        val emitted = mutableListOf<Long>()
        var damaged = 0
        val assembler = RtpH264AccessUnitAssembler(
            emit = { _, ts -> emitted.add(ts) },
            onDamaged = { damaged++ }
        )
        fun accept(b: ByteArray) {
            if (gate.classify(b) !=
                RtpSelectedPayloadGate.Verdict.FOREIGN_PAYLOAD)
                assembler.accept(b)
        }
        accept(packet(10,1000,nal=0x65))
        accept(packet(999,1000,pt=97,ssrc=3))
        // Missing the actual PT=96 packet #11 must still revoke the GOP.
        accept(packet(12,2000,nal=0x41))
        accept(packet(13,3000,nal=0x65))
        assertEquals(listOf(1000L,3000L),emitted)
        assertTrue(damaged >= 1)
        assertEquals(1L,gate.metrics().foreignPayload)
    }

    @Test fun malformedRtpRemainsVisibleForAssemblerQuarantine() {
        val gate = RtpSelectedPayloadGate(96)
        assertEquals(RtpSelectedPayloadGate.Verdict.MALFORMED,
            gate.classify(byteArrayOf(0x80.toByte(),0x60,0x01)))
        assertEquals(RtpSelectedPayloadGate.Verdict.MALFORMED,
            gate.classify(packet(1).apply { this[0]=0x40 }))
        assertEquals(RtpSelectedPayloadGate.Verdict.MALFORMED,
            gate.classify(packet(1).apply { this[0]=0x82.toByte() }))
        assertEquals(3L,gate.metrics().malformed)
        assertEquals(0L,gate.metrics().foreignPayload)
    }

    @Test fun unknownPayloadUsesLegacyCompatibilityInsteadOfInventingPt() {
        val gate = RtpSelectedPayloadGate(null)
        assertEquals(RtpSelectedPayloadGate.Verdict.SELECTED_VIDEO,
            gate.classify(packet(1,pt=96)))
        assertEquals(RtpSelectedPayloadGate.Verdict.SELECTED_VIDEO,
            gate.classify(packet(2,pt=110)))
        assertEquals(2L,gate.metrics().selected)
        assertEquals(0L,gate.metrics().foreignPayload)
    }

    @Test fun rejectsUnrepresentablePayloadTypes() {
        try {
            RtpSelectedPayloadGate(128)
            fail("Accepted invalid RTP PT")
        } catch (_: IllegalArgumentException) { }
        try {
            RtpSelectedPayloadGate(-1)
            fail("Accepted negative RTP PT")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun h264PayloadSelectedFromRtpmapIsEnforced() {
        val sdp = "m=video 0 RTP/AVP 96 98\r\n" +
            "a=rtpmap:96 H265/90000\r\n" +
            "a=rtpmap:98 H264/90000\r\n"
        val choice = H264SdpParameterSets.fromSdp(sdp)
        assertEquals(98, choice.payloadType)
        val gate = RtpSelectedPayloadGate(choice.payloadType)
        assertEquals(RtpSelectedPayloadGate.Verdict.FOREIGN_PAYLOAD,
            gate.classify(packet(1,pt=96)))
        assertEquals(RtpSelectedPayloadGate.Verdict.SELECTED_VIDEO,
            gate.classify(packet(1,pt=98)))
    }
}
