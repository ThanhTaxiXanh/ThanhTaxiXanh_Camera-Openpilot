package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

class OfflineYuvFixtureTest {
    private fun fixture(
        count:Int=2,
        gapMs:Long=80L,
        corruptDigest:Boolean=false
    ):ByteArray {
        val out=ByteArrayOutputStream()
        val data=DataOutputStream(out)
        data.write("TTXYUV01".toByteArray(Charsets.US_ASCII))
        data.writeShort(640)
        data.writeShort(480)
        data.writeShort(count)
        data.writeShort(0)
        repeat(count) { i->
            data.writeLong(1000L+i*gapMs)
            val y=ByteArray(OfflineYuvFixture.Y_BYTES) { if(i==0) 96.toByte() else 160.toByte() }
            val u=ByteArray(OfflineYuvFixture.UV_BYTES) {128.toByte()}
            val v=ByteArray(OfflineYuvFixture.UV_BYTES) {128.toByte()}
            data.write(y);data.write(u);data.write(v)
            val sha=MessageDigest.getInstance("SHA-256")
            sha.update(y);sha.update(u);sha.update(v)
            val digest=sha.digest()
            if(corruptDigest && i==1) digest[0]=(digest[0].toInt() xor 1).toByte()
            data.write(digest)
        }
        data.flush()
        return out.toByteArray()
    }

    @Test fun realBridgeAndTwoWarpsReplayChecksumCheckedFrames() {
        val bytes=fixture(count=3)
        val sequence=OfflineYuvFixture.read(ByteArrayInputStream(bytes))
        assertEquals(3,sequence.frames.size)
        assertEquals(1080L,sequence.frames[1].receivedMs)
        val result=OfflineYuvFixture.replay(sequence)
        assertEquals(3,result.frames)
        assertEquals(2,result.pairs)
        assertEquals(64,result.mainHash.length)
        assertEquals(64,result.wideHash.length)
        // Uniform gray planes intentionally produce identical tensor values
        // under both crop transforms. Distinct hashes would be an invalid
        // assertion unless the source contains spatial structure.
        assertEquals(result.mainHash,result.wideHash)
        assertTrue(result.note.contains("NOT actual camera video"))
        assertFalse(VisionSafetyGate.assess(
            null,null,nowElapsedMs=1_000L,speedMetersPerSecond=null).mayWarn)
    }

    @Test fun fixtureProducesDeterministicRawTensorFingerprints() {
        val v=OfflineYuvFixture.read(ByteArrayInputStream(fixture()))
        val a=OfflineYuvFixture.replay(v)
        val b=OfflineYuvFixture.replay(v)
        assertEquals(a.mainHash,b.mainHash)
        assertEquals(a.wideHash,b.wideHash)
    }

    @Test fun corruptedHeaderTimestampsDigestAndTrailingDataAreRejected() {
        val baseline=fixture()
        fun invalid(bytes:ByteArray) {
            try {
                OfflineYuvFixture.read(ByteArrayInputStream(bytes))
                fail("Malformed offline fixture accepted")
            } catch (_: IllegalArgumentException) {
                // Expected bounded parser rejection.
            } catch (_:java.io.EOFException) {
                // Truncation is invalid too.
            }
        }
        invalid(baseline.copyOfRange(0,baseline.size-100))
        invalid(baseline+"EXTRA".toByteArray(Charsets.US_ASCII))
        invalid(baseline.copyOf().apply { this[0]='X'.code.toByte() })
        invalid(baseline.copyOf().apply { this[9]=0 })
        invalid(baseline.copyOf().apply { this[13]=9 })
        invalid(fixture(gapMs=0L))
        invalid(fixture(gapMs=501L))
        invalid(fixture(corruptDigest=true))
        invalid(fixture(count=1))
        invalid(fixture(count=9))
    }

    @Test fun validFixtureFileSizeIsStrictlyBounded() {
        val data=fixture(count=OfflineYuvFixture.MAX_FRAMES)
        assertTrue(data.size.toLong()<=OfflineYuvFixture.MAX_FILE_BYTES)
        val parsed=OfflineYuvFixture.read(ByteArrayInputStream(data))
        assertEquals(OfflineYuvFixture.MAX_FRAMES,parsed.frames.size)
        assertEquals(OfflineYuvFixture.MAX_FILE_BYTES,data.size.toLong())
    }
}
