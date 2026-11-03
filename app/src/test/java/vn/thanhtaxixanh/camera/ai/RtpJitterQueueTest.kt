package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class RtpJitterQueueTest {
    private fun p(seq: Int): ByteArray {
        val b=ByteArray(14)
        b[0]=0x80.toByte()
        b[1]=96
        b[2]=(seq ushr 8).toByte()
        b[3]=seq.toByte()
        b[12]=0x65
        return b
    }
    private fun seq(b:ByteArray):Int =
        ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)

    @Test fun recoversOutOfOrderBeforeTimeout() {
        val q=RtpJitterQueue()
        val got=mutableListOf<Int>()
        val emit:(ByteArray)->Unit={got.add(seq(it))}
        q.accept(p(100),0,emit)
        q.accept(p(102),1,emit)
        assertEquals(listOf(100),got)
        q.accept(p(101),20,emit)
        assertEquals(listOf(100,101,102),got)
        assertEquals(1,q.recoveredOutOfOrderPackets)
        assertEquals(0,q.skippedMissingPackets)
    }

    @Test fun timeoutNeverStopsVideoForever() {
        val q=RtpJitterQueue(maxDelayMs=50)
        val got=mutableListOf<Int>()
        val emit:(ByteArray)->Unit={got.add(seq(it))}
        q.accept(p(100),0,emit)
        q.accept(p(102),1,emit)
        q.tick(70,emit)
        q.accept(p(103),80,emit)
        assertEquals(listOf(100,102,103),got)
        assertEquals(1,q.skippedMissingPackets)
    }

    @Test fun seqWrapAndDuplicates() {
        val q=RtpJitterQueue()
        val got=mutableListOf<Int>()
        val emit:(ByteArray)->Unit={got.add(seq(it))}
        q.accept(p(65534),0,emit)
        q.accept(p(0),1,emit)
        q.accept(p(65535),2,emit)
        q.accept(p(65535),3,emit)
        assertEquals(listOf(65534,65535,0),got)
        assertEquals(1,q.oldOrDuplicatePackets)
    }

    @Test fun boundedGapReleasesWithoutBacklog() {
        val q=RtpJitterQueue(capacity=4)
        val got=mutableListOf<Int>()
        val emit:(ByteArray)->Unit={got.add(seq(it))}
        q.accept(p(100),0,emit)
        q.accept(p(105),1,emit)
        q.accept(p(106),2,emit)
        assertEquals(listOf(100,105,106),got)
        assertEquals(4,q.skippedMissingPackets)
    }
}
