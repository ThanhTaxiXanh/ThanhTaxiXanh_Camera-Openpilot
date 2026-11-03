package vn.thanhtaxixanh.camera.ai

import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer

/**
 * Converts a client-owned MediaCodec Image into a safe CPU copy BEFORE the
 * codec releases its output buffer. Do not call getOutputImage on codecs
 * configured for Surface output: Android commonly returns null there.
 *
 * This class is used for a future off-screen YUV decoding path; the current
 * IJK/Surface preview is deliberately untouched.
 */
object AndroidYuvImageBridge {
    fun snapshot(image: Image): Yuv420FrameConverter.Frame {
        require(image.format == ImageFormat.YUV_420_888)
        val crop=image.cropRect
        val width=crop.width()
        val height=crop.height()
        require(width > 1 && height > 1 && image.planes.size == 3)
        fun plane(index:Int, chroma:Boolean):Yuv420FrameConverter.Plane {
            val p=image.planes[index]
            val b=p.buffer.duplicate()
            b.clear()
            val bytes=ByteArray(b.remaining())
            b.get(bytes)
            val offset=if(chroma)
                (crop.top / 2)*p.rowStride+(crop.left / 2)*p.pixelStride
                else crop.top*p.rowStride+crop.left*p.pixelStride
            return Yuv420FrameConverter.Plane(bytes,p.rowStride,p.pixelStride,offset)
        }
        return Yuv420FrameConverter.Frame(
            width,height,plane(0,false),plane(1,true),plane(2,true))
    }
}
