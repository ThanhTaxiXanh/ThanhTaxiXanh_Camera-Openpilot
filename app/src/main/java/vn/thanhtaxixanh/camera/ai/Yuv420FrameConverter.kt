package vn.thanhtaxixanh.camera.ai

/**
 * Pure byte-stride YUV_420_888 converter.
 *
 * Handles planar YUV and pixelStride=2 UV planes (NV12/NV21 view) with
 * separate U/V offsets, row padding and crop. No Android class in this
 * implementation, allowing deterministic host/JUnit tests.
 */
object Yuv420FrameConverter {
    data class Plane(
        val bytes: ByteArray,
        val rowStride: Int,
        val pixelStride: Int,
        val offset: Int = 0
    ) {
        init {
            require(rowStride > 0 && pixelStride > 0 && offset >= 0)
        }
        fun sample(x:Int,y:Int):Int {
            val index=offset.toLong()+y.toLong()*rowStride+x.toLong()*pixelStride
            require(x >= 0 && y >= 0 && index in 0L until bytes.size.toLong()) {
                "Invalid YUV plane bounds: x=$x y=$y offset=$offset"
            }
            return bytes[index.toInt()].toInt() and 255
        }
    }

    data class Frame(
        val width:Int,
        val height:Int,
        val y:Plane,
        val u:Plane,
        val v:Plane,
        val chromaXPhase:Int=0,
        val chromaYPhase:Int=0
    ) {
        init {
            require(width in 2..8192 && height in 2..8192)
            require(chromaXPhase in 0..1 && chromaYPhase in 0..1)
        }
    }

    fun argb(frame:Frame):IntArray {
        val width=frame.width
        val height=frame.height
        val result=IntArray(width*height)
        var i=0
        for (row in 0 until height) {
            for (col in 0 until width) {
                val yy=frame.y.sample(col,row)
                val cx=(col+frame.chromaXPhase)/2
                val cy=(row+frame.chromaYPhase)/2
                val uu=frame.u.sample(cx,cy)
                val vv=frame.v.sample(cx,cy)
                val c=(yy-16).coerceAtLeast(0)
                val d=uu-128
                val e=vv-128
                val red=((298*c+409*e+128) shr 8).coerceIn(0,255)
                val green=((298*c-100*d-208*e+128) shr 8).coerceIn(0,255)
                val blue=((298*c+516*d+128) shr 8).coerceIn(0,255)
                result[i++]=0xff000000.toInt() or
                    (red shl 16) or (green shl 8) or blue
            }
        }
        return result
    }
}
