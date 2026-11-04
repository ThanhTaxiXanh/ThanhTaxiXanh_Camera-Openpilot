package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class RtpH264AccessUnitAssemblerTest {
    private fun packet(seq:Int, stamp:Long, marked:Boolean, payload:ByteArray,
        padding:Int=0, extension:Boolean=false):ByteArray {
        val ext = if (extension) byteArrayOf(0, 1, 0, 1, 7, 8, 9, 10) else byteArrayOf()
        val out=ByteArray(12+ext.size+payload.size+padding)
        out[0]=(0x80 or (if(padding>0)0x20 else 0) or
            (if(extension)0x10 else 0)).toByte()
        out[1]=((if(marked)0x80 else 0) or 96).toByte()
        out[2]=(seq ushr 8).toByte();out[3]=seq.toByte()
        for(i in 0..3) out[4+i]=(stamp ushr (8*(3-i))).toByte()
        out[8]=1 // stable SSRC
        ext.copyInto(out,12)
        payload.copyInto(out,12+ext.size)
        if(padding>0) out[out.lastIndex]=padding.toByte()
        return out
    }
    private val idr=byteArrayOf(0x65,0x33,0x44)
    private val pframe=byteArrayOf(0x41,0x55)
    private fun fixture():Triple<RtpH264AccessUnitAssembler,
        MutableList<Pair<ByteArray,Long>>,IntArray> {
        val emitted=mutableListOf<Pair<ByteArray,Long>>()
        val counters=IntArray(3) // damaged, lost, discarded
        val decoder=RtpH264AccessUnitAssembler(
            emit={bytes,stamp->emitted.add(bytes to stamp)},
            onDamaged={counters[0]++},
            onLostSequence={counters[1]++},
            onDiscarded={counters[2]++})
        return Triple(decoder,emitted,counters)
    }
    @Test fun completeIdrThenPFrameEmittedInOrder() {
        val (a,out,metrics)=fixture()
        a.accept(packet(7,3000,false,byteArrayOf(0x67,0x01)))
        a.accept(packet(8,3000,true,idr))
        a.accept(packet(9,6000,true,pframe))
        assertEquals(2,out.size)
        assertEquals(3000L,out[0].second)
        assertTrue(H264AnnexB.hasIdr(out[0].first))
        assertEquals(0,metrics[0])
        assertFalse(a.needsIdr())
    }
    @Test fun fragmentedIdrReconstructedOnlyAfterFuEnd() {
        val (a,out,_) = fixture()
        a.accept(packet(1,1000,false,byteArrayOf(0x7c,0x85,0x11)))
        a.accept(packet(2,1000,false,byteArrayOf(0x7c,0x05,0x22)))
        a.accept(packet(3,1000,true,byteArrayOf(0x7c,0x45,0x33)))
        assertEquals(1,out.size)
        assertArrayEquals(byteArrayOf(0,0,0,1,0x65,0x11,0x22,0x33),
            out[0].first)
    }
    @Test fun missingFuEndAtTimestampBoundaryDiscardsIncompleteNal() {
        val (a,out,metrics)=fixture()
        a.accept(packet(1,1000,false,byteArrayOf(0x7c,0x85,0x11)))
        a.accept(packet(2,1000,false,byteArrayOf(0x7c,0x05,0x22)))
        a.accept(packet(3,2000,true,idr))
        assertEquals(1,metrics[0])
        assertEquals(1,metrics[2])
        assertEquals(1,out.size)
        assertEquals(2000L,out[0].second)
    }
    @Test fun lostPacketAtTimestampSwitchCannotReleasePreviousPartialAu() {
        val (a,out,metrics)=fixture()
        a.accept(packet(10,1000,false,idr))
        a.accept(packet(12,2000,true,idr))
        assertEquals(0,out.size)
        assertEquals(1,metrics[1])
        assertEquals(2,metrics[2])
        a.accept(packet(13,3000,true,pframe))
        a.accept(packet(14,4000,true,idr))
        assertEquals(1,out.size)
        assertEquals(4000L,out.single().second)
    }
    @Test fun lostFuPacketRequiresFutureIdrInsteadOfForwardingBrokenChain() {
        val (a,out,metrics)=fixture()
        a.accept(packet(1,1000,false,byteArrayOf(0x7c,0x85,0x01)))
        a.accept(packet(3,1000,true,byteArrayOf(0x7c,0x45,0x02)))
        a.accept(packet(4,2000,true,pframe))
        a.accept(packet(5,3000,true,idr))
        assertEquals(1,out.size)
        assertEquals(3000L,out.single().second)
        assertEquals(1,metrics[1])
    }
    @Test fun stapAAndFragmentHeadersMustBeWellFormed() {
        val (a,out,metrics)=fixture()
        val stap=byteArrayOf(0x78,0,2,0x67,0x01,0,2,0x68,0x02)
        a.accept(packet(1,1000,false,stap))
        a.accept(packet(2,1000,true,idr))
        assertEquals(1,out.size)
        assertTrue(out[0].first.size>idr.size+4)
        a.accept(packet(3,2000,true,byteArrayOf(0x78,0,20,0x65)))
        assertEquals(1,metrics[0])
        a.accept(packet(4,3000,true,pframe))
        assertEquals(1,out.size)
        a.accept(packet(5,4000,true,idr))
        assertEquals(2,out.size)
    }
    @Test fun invalidOrphanFuIsDiscardedAndFutureIdrRecovers() {
        val (a,out,metrics)=fixture()
        a.accept(packet(1,1000,true,byteArrayOf(0x7c,0x45,0x55)))
        assertEquals(1,metrics[2])
        a.accept(packet(2,2000,true,pframe))
        a.accept(packet(3,3000,true,idr))
        assertEquals(1,out.size)
    }
    @Test fun sequenceWrapAndExtensionPaddingAreSupported() {
        val (a,out,metrics)=fixture()
        a.accept(packet(65535,1000,true,idr,padding=4,extension=true))
        a.accept(packet(0,2000,true,pframe))
        assertEquals(2,out.size)
        assertEquals(0,metrics[1])
        assertTrue(H264AnnexB.hasIdr(out[0].first))
    }
    @Test fun badExtensionPaddingAndOversizeFailClosed() {
        val (a,out,metrics)=fixture()
        val invalid=packet(1,1000,false,idr,extension=true).apply {
            this[14]=0x7f;this[15]=0x7f
        }
        a.accept(invalid)
        a.accept(packet(2,2000,true,pframe))
        a.accept(packet(3,3000,true,idr))
        assertEquals(1,out.size)
        assertTrue(metrics[0]>=1)
        val tooLarge=ByteArray(RtpH264AccessUnitAssembler.MAX_ACCESS_UNIT_BYTES+10)
        tooLarge[0]=0x65
        a.accept(packet(4,4000,true,tooLarge))
        assertEquals(1,out.size)
        a.accept(packet(5,5000,true,idr))
        assertEquals(2,out.size)
    }
}
