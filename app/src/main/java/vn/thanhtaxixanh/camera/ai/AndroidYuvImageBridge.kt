package vn.thanhtaxixanh.camera.ai

import android.graphics.ImageFormat
import android.media.Image

/** Copy offscreen MediaCodec Image planes, retaining odd crop chroma phase. */
object AndroidYuvImageBridge {
    fun snapshot(image:Image):Yuv420FrameConverter.Frame {
        require(image.format == ImageFormat.YUV_420_888)
        val crop=image.cropRect
        val width=crop.width()
        val height=crop.height()
        require(width > 1 && height > 1 && image.planes.size == 3)
        require(crop.left >= 0 && crop.top >= 0 &&
            crop.right <= image.width && crop.bottom <= image.height)
        val phaseX=crop.left and 1
        val phaseY=crop.top and 1
        fun plane(index:Int,chroma:Boolean):Yuv420FrameConverter.Plane {
            val p=image.planes[index]
            return YuvPlaneSnapshot.copy(
                p.buffer,p.rowStride,p.pixelStride,
                if(chroma) crop.left/2 else crop.left,
                if(chroma) crop.top/2 else crop.top,
                if(chroma) (width+phaseX+1)/2 else width,
                if(chroma) (height+phaseY+1)/2 else height
            )
        }
        return Yuv420FrameConverter.Frame(
            width,height,plane(0,false),plane(1,true),plane(2,true),
            phaseX,phaseY
        )
    }
}
