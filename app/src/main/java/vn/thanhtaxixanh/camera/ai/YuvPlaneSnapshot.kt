package vn.thanhtaxixanh.camera.ai

import java.nio.ByteBuffer

/** Safe CPU copy of the valid bytes used by an Android YUV_420_888 plane. */
object YuvPlaneSnapshot {
    fun copy(
        source:ByteBuffer, rowStride:Int, pixelStride:Int,
        startX:Int, startY:Int, sampleWidth:Int, sampleHeight:Int
    ):Yuv420FrameConverter.Plane {
        require(rowStride > 0 && pixelStride > 0)
        require(startX >= 0 && startY >= 0 && sampleWidth > 0 && sampleHeight > 0)
        val start=startY.toLong()*rowStride+startX.toLong()*pixelStride
        val end=start+(sampleHeight-1L)*rowStride+(sampleWidth-1L)*pixelStride
        require(start >= 0L && end >= start && end < source.limit().toLong()) {
            "Cropped YUV plane exceeds the valid native ByteBuffer limit"
        }
        require(end < Int.MAX_VALUE)
        val native=source.duplicate()
        native.position(0)
        native.limit((end+1).toInt()) // preserve source limit, not capacity
        val copy=ByteArray((end+1).toInt())
        native.get(copy)
        return Yuv420FrameConverter.Plane(copy,rowStride,pixelStride,start.toInt())
    }
}
