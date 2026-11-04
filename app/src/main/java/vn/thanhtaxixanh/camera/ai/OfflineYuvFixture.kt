package vn.thanhtaxixanh.camera.ai

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * TTXYUV01 is a bounded user-supplied YUV420p test file (big endian).
 * Header: magic[8], width u16=640, height u16=480, frames u16=2..8,
 * flags u16=0. Each frame: local receiptMs i64, Y[307200], U[76800],
 * V[76800], SHA-256[32] over the three plane byte arrays.
 *
 * Frame digests detect accidental corruption, NOT provenance, legal rights,
 * camera exposure, model compatibility or ADAS safety.
 */
object OfflineYuvFixture {
    const val WIDTH=640
    const val HEIGHT=480
    const val MAX_FRAMES=8
    const val Y_BYTES=WIDTH*HEIGHT
    const val UV_BYTES=Y_BYTES/4
    const val MAX_FILE_BYTES=16L+MAX_FRAMES*(8L+Y_BYTES+2L*UV_BYTES+32L)
    private val magic="TTXYUV01".toByteArray(Charsets.US_ASCII)

    data class Frame(val receivedMs:Long,val y:ByteArray,val u:ByteArray,val v:ByteArray) {
        fun toYuv()=Yuv420FrameConverter.Frame(WIDTH,HEIGHT,
            Yuv420FrameConverter.Plane(y,WIDTH,1),
            Yuv420FrameConverter.Plane(u,WIDTH/2,1),
            Yuv420FrameConverter.Plane(v,WIDTH/2,1))
    }
    data class Sequence(val frames:List<Frame>)
    data class Replay(val frames:Int,val pairs:Int,val mainHash:String,val wideHash:String,
        val note:String="Virtual-clock RAW diagnostic only; NOT actual camera video, model output, or calibrated ADAS")

    fun read(input:InputStream):Sequence {
        val stream=DataInputStream(BufferedInputStream(input,65536))
        val head=ByteArray(8)
        stream.readFully(head)
        require(head.contentEquals(magic)) { "Invalid TTXYUV01 magic" }
        require(stream.readUnsignedShort()==WIDTH && stream.readUnsignedShort()==HEIGHT) {
            "Only 640x480 planar YUV is supported"
        }
        val count=stream.readUnsignedShort()
        require(count in 2..MAX_FRAMES && stream.readUnsignedShort()==0) {
            "Expected 2..8 frames with flags=0"
        }
        var previous=-1L
        val frames=ArrayList<Frame>(count)
        repeat(count) { index ->
            val at=stream.readLong()
            require(at>=0L && (index==0 ||
                (at>previous && at-previous<=A500sRawVisionBridge.MAX_GAP_MS))) {
                "Invalid YUV frame timestamp/temporal gap"
            }
            previous=at
            val y=ByteArray(Y_BYTES)
            val u=ByteArray(UV_BYTES)
            val v=ByteArray(UV_BYTES)
            stream.readFully(y);stream.readFully(u);stream.readFully(v)
            val claimed=ByteArray(32)
            stream.readFully(claimed)
            val digest=MessageDigest.getInstance("SHA-256")
            digest.update(y);digest.update(u);digest.update(v)
            require(MessageDigest.isEqual(claimed,digest.digest())) {
                "Frame $index checksum mismatch"
            }
            frames.add(Frame(at,y,u,v))
        }
        require(stream.read()==-1) { "Unexpected trailing fixture bytes" }
        return Sequence(frames)
    }

    /** Serialize canonical TTXYUV01 with digests, refusing partial bad inputs. */
    fun write(sequence: Sequence, output: java.io.OutputStream) {
        val frames=sequence.frames
        require(frames.size in 2..MAX_FRAMES)
        var previous=-1L
        for(frame in frames) {
            require(frame.receivedMs>=0L && (previous<0L ||
                (frame.receivedMs>previous &&
                    frame.receivedMs-previous<=A500sRawVisionBridge.MAX_GAP_MS)))
            require(frame.y.size==Y_BYTES && frame.u.size==UV_BYTES &&
                frame.v.size==UV_BYTES)
            previous=frame.receivedMs
        }
        val out=java.io.DataOutputStream(java.io.BufferedOutputStream(output,65536))
        out.write(magic)
        out.writeShort(WIDTH);out.writeShort(HEIGHT)
        out.writeShort(frames.size);out.writeShort(0)
        for(frame in frames) {
            out.writeLong(frame.receivedMs)
            out.write(frame.y);out.write(frame.u);out.write(frame.v)
            val sha=MessageDigest.getInstance("SHA-256")
            sha.update(frame.y);sha.update(frame.u);sha.update(frame.v)
            out.write(sha.digest())
        }
        out.flush()
    }

    private val mainWarp=VirtualCameraWarp.Matrix(doubleArrayOf(
        1.0,0.0,64.0,0.0,1.0,112.0,0.0,0.0,1.0))
    private val wideWarp=VirtualCameraWarp.Matrix(doubleArrayOf(
        1.2,0.0,13.0,0.0,1.2,86.0,0.0,0.0,1.0))

    private fun hash(values:FloatArray):String {
        val digest=MessageDigest.getInstance("SHA-256")
        for(value in values) {
            val bits=value.toRawBits()
            digest.update((bits ushr 24).toByte())
            digest.update((bits ushr 16).toByte())
            digest.update((bits ushr 8).toByte())
            digest.update(bits.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /**
     * The exact production bridge/packer and both existing experimental
     * warps are exercised. The injected time is VIRTUAL and never treated
     * as measured inference or camera latency. No ONNX model is executed.
     */
    fun replay(
        sequence:Sequence,
        onFirstTensorPair: ((FloatArray,FloatArray)->Unit)? = null
    ):Replay {
        require(sequence.frames.size in 2..MAX_FRAMES)
        var nowMs=0L
        var pairs=0
        var mainHash=""
        var wideHash=""
        val bridge=A500sRawVisionBridge(nowMs={nowMs},submit={pair->
            val main=VirtualCameraWarp.pairToModelInput(
                pair.previousArgb,pair.currentArgb,WIDTH,HEIGHT,mainWarp)
            val wide=VirtualCameraWarp.pairToModelInput(
                pair.previousArgb,pair.currentArgb,WIDTH,HEIGHT,wideWarp)
            VisionInputContract.requireRawStudioByteTensors(main,wide)
            if(pairs==0) {
                mainHash=hash(main)
                wideHash=hash(wide)
                // First temporal pair may be checked against a separate
                // user-authorized TTXREF01 reference, strictly offline.
                // No ONNX runner is opened unless the caller requests it.
                onFirstTensorPair?.invoke(main,wide)
            }
            pairs++
            true
        })
        for((index,frame) in sequence.frames.withIndex()) {
            nowMs=frame.receivedMs+10L
            val verdict=bridge.accept(frame.toYuv(),frame.receivedMs)
            val expected=if(index==0) A500sRawVisionBridge.Verdict.WAITING_PAIR
                else A500sRawVisionBridge.Verdict.SUBMITTED
            require(verdict==expected) { "Offline replay rejected frame $index: $verdict" }
        }
        require(pairs==sequence.frames.size-1)
        return Replay(sequence.frames.size,pairs,mainHash,wideHash)
    }
}
