package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class H264AnnexBTest {
    private fun nal(type: Int, fourByte: Boolean = true): ByteArray {
        val prefix = if (fourByte) byteArrayOf(0, 0, 0, 1)
            else byteArrayOf(0, 0, 1)
        return prefix + byteArrayOf(type.toByte(), 9, 8, 7)
    }

    @Test fun recognizesIdrWithBothStartCodeLengths() {
        assertTrue(H264AnnexB.hasIdr(nal(0x65)))
        assertTrue(H264AnnexB.hasIdr(nal(0x65, false)))
        assertTrue(H264AnnexB.hasIdr(nal(0x67) + nal(0x68, false) + nal(0x65)))
    }

    @Test fun neverMistakesPayloadBytesForIdr() {
        assertFalse(H264AnnexB.hasIdr(nal(0x41) + byteArrayOf(0x65)))
        assertFalse(H264AnnexB.hasIdr(byteArrayOf(0x65, 0, 0, 2, 0x65)))
        assertFalse(H264AnnexB.hasIdr(nal(0x67) + nal(0x68)))
        assertFalse(H264AnnexB.hasIdr(byteArrayOf(0, 0, 0, 1)))
        assertFalse(H264AnnexB.hasIdr(byteArrayOf()))
    }

    @Test fun gateNeverAdmitsDependentFrameBeforeIdr() {
        val gate = H264SecondarySyncGate()
        assertTrue(gate.awaitingIdr())
        assertFalse(gate.accept(nal(0x41)))
        assertFalse(gate.accept(nal(0x67) + nal(0x68)))
        assertTrue(gate.accept(nal(0x65)))
        assertFalse(gate.awaitingIdr())
        assertTrue(gate.accept(nal(0x41)))
    }

    @Test fun afterLossEntireGopIsDiscardedUntilNextIdr() {
        val gate = H264SecondarySyncGate()
        assertTrue(gate.accept(nal(0x65)))
        assertTrue(gate.accept(nal(0x41)))
        gate.reset()
        assertTrue(gate.awaitingIdr())
        for (i in 0 until 25) assertFalse(gate.accept(nal(0x41)))
        assertTrue(gate.accept(nal(0x65, false)))
        assertTrue(gate.accept(nal(0x41)))
    }
}
