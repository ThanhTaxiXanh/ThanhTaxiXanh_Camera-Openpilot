package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** Synthetic RTP bytes, pure JVM: no A500S or native decoder evidence. */
class PreviewGopEpochFenceTest {
    @Test fun damageBeforeAnyQueuedAuOnlyAwaitsIdr() {
        val fence=PreviewGopEpochFence()
        assertEquals(PreviewGopEpochFence.Recovery.AWAIT_FIRST_IDR,
            fence.damaged())
        assertTrue(fence.mayQueue())
        fence.queued()
        assertEquals(1L,fence.snapshot().queuedAccessUnits)
        assertEquals(0L,fence.snapshot().reconnectRequests)
    }

    @Test fun damageAfterPreviewAuRevokesOldCodecEpoch() {
        val fence=PreviewGopEpochFence()
        repeat(2) { fence.queued() }
        assertEquals(PreviewGopEpochFence.Recovery.RECONNECT_PREVIEW,
            fence.damaged())
        assertFalse(fence.mayQueue())
        assertEquals(1L,fence.snapshot().reconnectRequests)
        try {
            fence.queued()
            fail("Old MediaCodec must not accept a future H264 AU")
        } catch (_:IllegalStateException) {}
    }

    @Test fun repeatedDamageRequiresOnlyOneReconnect() {
        val fence=PreviewGopEpochFence()
        fence.queued()
        assertEquals(PreviewGopEpochFence.Recovery.RECONNECT_PREVIEW,
            fence.damaged())
        repeat(40) {
            assertEquals(PreviewGopEpochFence.Recovery.RECONNECT_PENDING,
                fence.damaged())
        }
        assertEquals(1L,fence.snapshot().reconnectRequests)
        assertEquals(41L,fence.snapshot().damageEvents)
    }

    @Test fun cleanTwentyThousandAuTraceNeverRevokesEpoch() {
        val fence=PreviewGopEpochFence()
        repeat(20_000) {
            assertTrue(fence.mayQueue())
            fence.queued()
        }
        assertEquals(20_000L,fence.snapshot().queuedAccessUnits)
        assertFalse(fence.snapshot().reconnectRequired)
    }

    private fun packet(seq:Int,ts:Long,mark:Boolean,payload:ByteArray):ByteArray {
        val b=ByteArray(12+payload.size)
        b[0]=0x80.toByte()
        b[1]=(if(mark) 0xe0 else 0x60).toByte()
        b[2]=(seq ushr 8).toByte()
        b[3]=seq.toByte()
        for (i in 0..3) b[4+i]=(ts ushr (24-8*i)).toByte()
        b[8]=1
        payload.copyInto(b,12)
        return b
    }

    @Test fun lostRtpAfterIdrPreventsOldSessionTakingFreshIdr() {
        val fence=PreviewGopEpochFence()
        val got=mutableListOf<Long>()
        var firstDamage:PreviewGopEpochFence.Recovery?=null
        val assembler=RtpH264AccessUnitAssembler(
            emit={_,stamp->
                if (fence.mayQueue()) {
                    fence.queued()
                    got.add(stamp)
                }
            },
            onDamaged={
                val verdict=fence.damaged()
                if(firstDamage==null) firstDamage=verdict
            })
        val idr=byteArrayOf(0x65,0x11)
        val p=byteArrayOf(0x41,0x22)
        assembler.accept(packet(1,1000,true,idr))
        assembler.accept(packet(2,2000,true,p))
        assembler.accept(packet(4,3000,true,p)) // lost seq=3
        assembler.accept(packet(5,4000,true,idr))
        assertEquals(listOf(1000L,2000L),got)
        assertEquals(PreviewGopEpochFence.Recovery.RECONNECT_PREVIEW,
            firstDamage)
        assertTrue(fence.snapshot().reconnectRequired)
        assertEquals(1L,fence.snapshot().reconnectRequests)
        assertTrue(PreviewGopEpochFence().mayQueue()) // new player
    }

    @Test fun incompleteFuBeforeFirstIdrDoesNotRestartUnusedDecoder() {
        val fence=PreviewGopEpochFence()
        val got=mutableListOf<Long>()
        val assembler=RtpH264AccessUnitAssembler(
            emit={_,ts->fence.queued();got.add(ts)},
            onDamaged={
                assertEquals(PreviewGopEpochFence.Recovery.AWAIT_FIRST_IDR,
                    fence.damaged())
            })
        assembler.accept(packet(1,1000,false,
            byteArrayOf(0x7c,0x85.toByte(),0x11)))
        assembler.accept(packet(2,2000,true,byteArrayOf(0x65,0x22)))
        assertEquals(listOf(2000L),got)
        assertEquals(0L,fence.snapshot().reconnectRequests)
    }

    @Test fun udpTimeoutThenFreshIdrCannotReenterOldPreviewDecoder() {
        val fence = PreviewGopEpochFence()
        val accepted = mutableListOf<Long>()
        var damage = 0
        val assembler = RtpH264AccessUnitAssembler(
            emit = { _, pts ->
                if (fence.mayQueue()) {
                    fence.queued()
                    accepted.add(pts)
                }
            },
            onDamaged = { fence.damaged(); damage++ }
        )
        val udp = RtpJitterQueue(maxDelayMs = 55L)
        val deliver: (ByteArray) -> Unit = { assembler.accept(it) }
        val idr = byteArrayOf(0x65, 0x11)
        val p = byteArrayOf(0x41, 0x22)

        // RTP seq=101 never arrives. The UDP window must not invent it.
        udp.accept(packet(100, 1000, true, idr), 0L, deliver)
        udp.accept(packet(102, 3000, true, p), 1L, deliver)
        assertEquals(listOf(1000L), accepted)
        udp.tick(60L, deliver)
        assertEquals(1L, udp.skippedMissingPackets)
        assertTrue(damage >= 1)
        assertTrue(fence.snapshot().reconnectRequired)

        // Even a valid later IDR must be decoded by a NEW MediaCodec.
        udp.accept(packet(103, 4000, true, idr), 61L, deliver)
        assertEquals(listOf(1000L), accepted)
        assertEquals(1L, fence.snapshot().reconnectRequests)
        assertFalse(fence.mayQueue())
    }

}
