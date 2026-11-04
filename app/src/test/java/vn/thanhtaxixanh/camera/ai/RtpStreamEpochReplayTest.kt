package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** Synthetic network bytes only; NOT Android codec or A500S camera proof. */
class RtpStreamEpochReplayTest {
    private fun packet(seq:Int, stamp:Long, source:Long,
        nal:ByteArray, marker:Boolean=true, pt:Int=96):ByteArray {
        val p=ByteArray(12+nal.size)
        p[0]=0x80.toByte()
        p[1]=((if(marker) 0x80 else 0) or pt).toByte()
        p[2]=(seq ushr 8).toByte();p[3]=seq.toByte()
        for (i in 0..3) {
            p[4+i]=(stamp ushr (24-8*i)).toByte()
            p[8+i]=(source ushr (24-8*i)).toByte()
        }
        nal.copyInto(p,12)
        return p
    }
    private fun nal(i:Int, idr:Boolean):ByteArray =
        byteArrayOf((if(idr) 0x65 else 0x41).toByte(),
            (i ushr 16).toByte(),(i ushr 8).toByte(),i.toByte(),
            0x24,0x55,0x31,0x19)
    private fun fragments(nal:ByteArray):Pair<ByteArray,ByteArray> {
        val type=nal[0].toInt() and 0x1f
        val indicator=((nal[0].toInt() and 0xe0) or 28).toByte()
        return (byteArrayOf(indicator,(0x80 or type).toByte())+
                nal.copyOfRange(1,4)) to
            (byteArrayOf(indicator,(0x40 or type).toByte())+
                nal.copyOfRange(4,nal.size))
    }

    @Test fun tenMinuteDeterministicUdpReplayQuarantinesLossAndSourceChanges() {
        val expected=HashMap<Long,ByteArray>()
        val delivered=ArrayList<Long>()
        var requiredIdr=true
        var damaged=0
        var skipped=0
        val assembler=RtpH264AccessUnitAssembler(
            emit={bytes,stamp->
                assertTrue("Out-of-order AU PTS "+stamp,
                    delivered.isEmpty() || stamp>delivered.last())
                if(requiredIdr) assertTrue("P frame after corruption",
                    H264AnnexB.hasIdr(bytes))
                val truth=expected[stamp]
                assertNotNull("Unexpected RTP AU PTS "+stamp,truth)
                assertArrayEquals(byteArrayOf(0,0,0,1)+truth!!,bytes)
                delivered.add(stamp)
                requiredIdr=false
            },
            onDamaged={requiredIdr=true;damaged++},
            onLostSequence={skipped++})
        val queue=RtpJitterQueue(capacity=32,maxDelayMs=55L)
        val send:(ByteArray,Long)->Unit={data,time->
            queue.accept(data,time){assembler.accept(it)}
        }

        var sequence=65525
        val totalFrames=18_000 // 10 minutes at 30 Hz (virtual clock)
        for(i in 0 until totalFrames) {
            val at=i.toLong()*33L
            val pts=500_000L+i.toLong()*3000L
            val isIdr=i%30==0
            val source=if(i<9013) 0x11223344L else 0x55667788L
            val picture=nal(i,isIdr)
            expected[pts]=picture
            if(i==9013) sequence=15000 // new SSRC/sequence domain
            if(i>0 && i%47==0 && !isIdr) {
                // One missing RTP packet: no synthetic replacement inserted.
                sequence=(sequence+1) and 65535
            } else if(i%43==0 && i>0) {
                val (first,last)=fragments(picture)
                val a=packet(sequence,pts,source,first,false)
                sequence=(sequence+1) and 65535
                val b=packet(sequence,pts,source,last,true)
                sequence=(sequence+1) and 65535
                if(i%86==0) {
                    // Ending fragment arrives first, but within reorder limit.
                    send(b,at)
                    send(a,at+8)
                } else {
                    send(a,at)
                    send(b,at+8)
                }
            } else {
                send(packet(sequence,pts,source,picture),at)
                sequence=(sequence+1) and 65535
            }
            queue.tick(at+33L){assembler.accept(it)}
        }
        queue.tick(totalFrames*33L+500L){assembler.accept(it)}
        assertTrue("Replay output count "+delivered.size,delivered.size>8_000)
        assertTrue("No packet loss injected",skipped>50)
        assertTrue("No damaged AU detected",damaged>50)
        assertTrue("No reordered fragments recovered",
            queue.recoveredOutOfOrderPackets>40L)
        assertEquals(1L,queue.sourceEpochResets)
        assertTrue(delivered.last()>50_000_000L)
    }

    @Test fun jitterFlushesOldEpochBeforeNewIdrAndSkipsStalePending() {
        val queue=RtpJitterQueue(maxDelayMs=55L)
        val out=mutableListOf<Int>()
        val take:(ByteArray)->Unit={p->
            out.add(((p[2].toInt() and 255) shl 8) or
                (p[3].toInt() and 255))
        }
        val idr=nal(1,true)
        queue.accept(packet(10,1000,0x1234,idr),0,take)
        queue.accept(packet(12,2000,0x1234,idr),1,take) // pending
        queue.accept(packet(900,3000,0x5678,idr),2,take)
        queue.accept(packet(901,4000,0x5678,idr),3,take)
        queue.tick(100,take)
        assertEquals(listOf(10,900,901),out)
        assertEquals(1L,queue.sourceEpochResets)
    }

    @Test fun sourceSwitchAndFuHeaderMismatchMustWaitForNextIdr() {
        val emitted=mutableListOf<Long>()
        var damaged=0
        val assembler=RtpH264AccessUnitAssembler(
            emit={_,pts->emitted.add(pts)},onDamaged={damaged++})
        val start=byteArrayOf(0x7c,0x85.toByte(),0x11)
        val wrongNri=byteArrayOf(0x5c,0x45,0x22)
        assembler.accept(packet(1,1000,1,start,false))
        assembler.accept(packet(2,1000,1,wrongNri))
        assembler.accept(packet(3,2000,1,nal(2,false)))
        assertTrue(assembler.needsIdr())
        assembler.accept(packet(4,3000,1,nal(3,true)))
        assembler.accept(packet(70,4000,2,nal(4,false)))
        assembler.accept(packet(71,5000,2,nal(5,true)))
        assertEquals(listOf(3000L,5000L),emitted)
        assertTrue(damaged>=2)
    }

    @Test fun payloadTypeChangeAndForbiddenNalAreIsolated() {
        val emitted=mutableListOf<Long>()
        val assembler=RtpH264AccessUnitAssembler(
            emit={_,pts->emitted.add(pts)},onDamaged={})
        assembler.accept(packet(1,1000,1,nal(0,true)))
        assembler.accept(packet(2,2000,1,nal(1,false),pt=97))
        assembler.accept(packet(3,3000,1,nal(2,true),pt=97))
        assembler.accept(packet(4,4000,1,byteArrayOf(0xe5.toByte(),0x20),pt=97))
        assembler.accept(packet(5,5000,1,nal(4,false),pt=97))
        assembler.accept(packet(6,6000,1,nal(5,true),pt=97))
        assertEquals(listOf(1000L,3000L,6000L),emitted)
    }
}
