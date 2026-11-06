package vn.thanhtaxixanh.camera.ai

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * TTXYUV02 is a NON-TEMPORAL diagnostic capture of 1..4 genuine decoded
 * YUV420p frames. It deliberately permits receipt gaps over 500ms and
 * MUST NOT be loaded by TTXYUV01/OfflineYuvFixture.replay or represented
 * as an A500S temporal pair or valid ONNX inference input.
 *
 * Header: ASCII magic[8], width u16, height u16, count u16, flags u16=1
 * (NON_TEMPORAL). Each frame: local receipt elapsedMs i64, raw planar
 * Y[307200], U[76800], V[76800], SHA256(Y+U+V)[32].
 * SHA checks accidental file corruption, not camera/source provenance.
 */
object OfflineYuvSnapshotFixture {
    const val MAX_FRAMES=4
    const val NON_TEMPORAL_FLAG=1
    private val magic="TTXYUV02".toByteArray(Charsets.US_ASCII)

    data class Snapshot(val frames:List<OfflineYuvFixture.Frame>) {
        val nonTemporalGaps: Int get() = frames.zipWithNext().count { (a,b) ->
            b.receivedMs-a.receivedMs>A500sRawVisionBridge.MAX_GAP_MS
        }
    }

    fun write(snapshot: Snapshot, output: OutputStream) {
        validate(snapshot.frames)
        val out=DataOutputStream(BufferedOutputStream(output,65536))
        out.write(magic)
        out.writeShort(OfflineYuvFixture.WIDTH)
        out.writeShort(OfflineYuvFixture.HEIGHT)
        out.writeShort(snapshot.frames.size)
        out.writeShort(NON_TEMPORAL_FLAG)
        for (frame in snapshot.frames) {
            out.writeLong(frame.receivedMs)
            out.write(frame.y);out.write(frame.u);out.write(frame.v)
            out.write(digest(frame))
        }
        out.flush()
    }

    fun read(input:InputStream): Snapshot {
        val stream=DataInputStream(BufferedInputStream(input,65536))
        val bytes=ByteArray(8)
        stream.readFully(bytes)
        require(bytes.contentEquals(magic)) { "Not TTXYUV02" }
        require(stream.readUnsignedShort()==OfflineYuvFixture.WIDTH &&
            stream.readUnsignedShort()==OfflineYuvFixture.HEIGHT) {
            "Unsupported diagnostic YUV dimensions"
        }
        val count=stream.readUnsignedShort()
        val flags=stream.readUnsignedShort()
        require(count in 1..MAX_FRAMES && flags==NON_TEMPORAL_FLAG) {
            "Expected 1..4 non-temporal real YUV frames"
        }
        val frames=ArrayList<OfflineYuvFixture.Frame>(count)
        var previous=-1L
        repeat(count) { index ->
            val time=stream.readLong()
            require(time>=0L && (index==0 || time>previous)) {
                "Non-monotonic YUV receipt clock"
            }
            previous=time
            val y=ByteArray(OfflineYuvFixture.Y_BYTES)
            val u=ByteArray(OfflineYuvFixture.UV_BYTES)
            val v=ByteArray(OfflineYuvFixture.UV_BYTES)
            stream.readFully(y);stream.readFully(u);stream.readFully(v)
            val claimed=ByteArray(32)
            stream.readFully(claimed)
            val frame=OfflineYuvFixture.Frame(time,y,u,v)
            require(MessageDigest.isEqual(claimed,digest(frame))) {
                "TTXYUV02 frame ${index} SHA-256 mismatch"
            }
            frames.add(frame)
        }
        require(stream.read()==-1) { "TTXYUV02 trailing bytes" }
        return Snapshot(frames)
    }

    private fun validate(frames:List<OfflineYuvFixture.Frame>) {
        require(frames.size in 1..MAX_FRAMES)
        var previous=-1L
        for (frame in frames) {
            require(frame.receivedMs>=0L &&
                (previous<0L || frame.receivedMs>previous)) {
                "TTXYUV02 local receipt times must be monotonic"
            }
            require(frame.y.size==OfflineYuvFixture.Y_BYTES &&
                frame.u.size==OfflineYuvFixture.UV_BYTES &&
                frame.v.size==OfflineYuvFixture.UV_BYTES) {
                "TTXYUV02 invalid plane sizes"
            }
            previous=frame.receivedMs
        }
    }

    private fun digest(frame:OfflineYuvFixture.Frame):ByteArray =
        MessageDigest.getInstance("SHA-256").apply {
            update(frame.y);update(frame.u);update(frame.v)
        }.digest()
}

/**
 * Bounded RAM-only "flight recorder": owner has explicitly turned on YUV
 * before frames are buffered. Keeps the FIRST four real decoder outputs
 * (roughly 1.85 MB), even if the optional codec later stalls/reconnects.
 * Nothing is written to disk until owner explicitly exports via SAF.
 *
 * TTXYUV02 samples are NOT temporal ONNX pairs. They are separately
 * identifiable independent evidence of the secondary YUV decoder output.
 */
class A500sYuvSnapshotCapture(private val target:Int=4) {
    init { require(target in 1..OfflineYuvSnapshotFixture.MAX_FRAMES) }
    private val frames=ArrayList<OfflineYuvFixture.Frame>(target)
    private var enabled=false
    private var rejected=0L

    @Synchronized fun enable() { frames.clear();rejected=0L;enabled=true }
    @Synchronized fun disable() { frames.clear(); enabled=false }
    @Synchronized fun count():Int = frames.size
    @Synchronized fun rejectedFrames():Long=rejected
    @Synchronized fun isEnabled():Boolean=enabled

    /** Returns true only for a newly copied genuine 640x480 decoder frame. */
    @Synchronized fun accept(
        frame:Yuv420FrameConverter.Frame,
        atMs:Long
    ):Boolean {
        if(!enabled || frames.size>=target) return false
        val old=frames.lastOrNull()?.receivedMs
        if(frame.width!=640 || frame.height!=480 || atMs<0L ||
            (old!=null && atMs<=old)) {
            rejected++
            return false
        }
        fun bytes(plane:Yuv420FrameConverter.Plane,w:Int,h:Int):ByteArray {
            val dst=ByteArray(w*h)
            for(y in 0 until h) for(x in 0 until w)
                dst[y*w+x]=plane.sample(x,y).toByte()
            return dst
        }
        return try {
            frames.add(OfflineYuvFixture.Frame(
                atMs,bytes(frame.y,640,480),bytes(frame.u,320,240),
                bytes(frame.v,320,240)))
            true
        } catch (_:IllegalArgumentException) { rejected++;false }
        catch (_:IndexOutOfBoundsException) { rejected++;false }
    }

    @Synchronized fun snapshot():OfflineYuvSnapshotFixture.Snapshot? =
        if(!enabled || frames.isEmpty()) null
        else OfflineYuvSnapshotFixture.Snapshot(frames.toList())
}
