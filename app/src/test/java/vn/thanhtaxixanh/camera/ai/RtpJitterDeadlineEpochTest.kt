package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure UDP reordering scenarios. The real A500S socket has a shorter
 * receive timeout, but these tests use explicit virtual elapsed times.
 */
class RtpJitterDeadlineEpochTest {
    private fun packet(seq:Int, ssrc:Long=1L, pt:Int=96):ByteArray {
        val b=ByteArray(14)
        b[0]=0x80.toByte()
        b[1]=(0x80 or pt).toByte()
        b[2]=(seq ushr 8).toByte()
        b[3]=seq.toByte()
        for(i in 0..3) b[8+i]=(ssrc ushr (24-8*i)).toByte()
        b[12]=0x65
        b[13]=1
        return b
    }
    private fun seq(b:ByteArray):Int =
        ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)

    @Test fun singleTickReleasesEveryExpiredIndependentGap() {
        val q=RtpJitterQueue(maxDelayMs=55)
        val output=mutableListOf<Int>()
        val send:(ByteArray)->Unit={output.add(seq(it))}
        q.accept(packet(100),0,send)
        q.accept(packet(102),1,send) // 101 missing
        q.accept(packet(104),2,send) // 103 missing
        q.tick(54,send)
        assertEquals(listOf(100),output)
        q.tick(57,send)
        assertEquals(listOf(100,102,104),output)
        assertEquals(2L,q.skippedMissingPackets)
    }

    @Test fun latePreviousSourceCannotReverseNewStreamEpoch() {
        val q=RtpJitterQueue()
        val out=mutableListOf<Int>()
        val send:(ByteArray)->Unit={out.add(seq(it))}
        q.accept(packet(10,1),0,send)
        q.accept(packet(900,2),1,send) // new SSRC
        q.accept(packet(11,1),2,send) // delayed old-source packet
        q.accept(packet(901,2),3,send)
        q.accept(packet(902,2),4,send)
        assertEquals(listOf(10,900,901,902),out)
        assertEquals(1L,q.sourceEpochResets)
        assertEquals(1L,q.lateEpochPackets)
        assertEquals(1L,q.oldOrDuplicatePackets)
    }

    @Test fun oldPendingPacketsAndOldPayloadTypeAreNotReplayed() {
        val q=RtpJitterQueue(maxDelayMs=55L)
        val out=mutableListOf<Int>()
        val send:(ByteArray)->Unit={out.add(seq(it))}
        q.accept(packet(100,9,96),0,send)
        q.accept(packet(102,9,96),1,send) // pending old epoch
        q.accept(packet(500,9,97),2,send) // new payload type
        q.accept(packet(101,9,96),3,send) // late old epoch
        q.accept(packet(501,9,97),4,send)
        q.tick(100,send)
        assertEquals(listOf(100,500,501),out)
        assertEquals(1L,q.sourceEpochResets)
        assertEquals(1L,q.lateEpochPackets)
    }

    @Test fun sourceCanReturnAfterBoundedQuarantine() {
        val q=RtpJitterQueue()
        val out=mutableListOf<Int>()
        val send:(ByteArray)->Unit={out.add(seq(it))}
        q.accept(packet(1,11),0,send)
        q.accept(packet(100,22),1,send)
        for (i in 1..260) q.accept(packet(100+i,22),(i+1).toLong(),send)
        q.accept(packet(5,11),300,send)
        assertEquals(2L,q.sourceEpochResets)
        assertEquals(0L,q.lateEpochPackets)
        assertEquals(5,out.last())
    }

    @Test fun sequenceWrapAndEpochQuarantineDoNotBreakNormalDelivery() {
        val q=RtpJitterQueue()
        val out=mutableListOf<Int>()
        val send:(ByteArray)->Unit={out.add(seq(it))}
        q.accept(packet(65535,1),0,send)
        q.accept(packet(0,1),1,send)
        q.accept(packet(300,2),2,send)
        q.accept(packet(1,1),3,send)
        q.accept(packet(301,2),4,send)
        assertEquals(listOf(65535,0,300,301),out)
        assertEquals(1L,q.sourceEpochResets)
        assertEquals(1L,q.lateEpochPackets)
    }
}
