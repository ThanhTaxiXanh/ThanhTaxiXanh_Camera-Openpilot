package vn.thanhtaxixanh.camera.ai

import kotlin.math.floor

/**
 * Deterministic perspective sampler used to prepare image pairs for the
 * Openpilot temporal YUV contract.
 *
 * Matrix is OUTPUT -> INPUT homogeneous coordinates, row-major 3x3.
 * The matrix must be derived from a validated camera model; identity and
 * synthetic transforms are for UNIT TESTS, not for live safety inference.
 */
object VirtualCameraWarp {
    const val OUTPUT_W = OpenpilotYuvPacker.SOURCE_WIDTH
    const val OUTPUT_H = OpenpilotYuvPacker.SOURCE_HEIGHT

    data class Matrix(val values: DoubleArray) {
        init {
            require(values.size == 9 && values.all { it.isFinite() })
            require(values[6] != 0.0 || values[7] != 0.0 || values[8] != 0.0)
        }
        companion object {
            fun identity() = Matrix(doubleArrayOf(
                1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0))
        }
    }

    fun warp(
        argb: IntArray,
        width: Int,
        height: Int,
        outputToInput: Matrix
    ): IntArray {
        require(width > 1 && height > 1)
        require(width.toLong() * height.toLong() == argb.size.toLong())
        val m = outputToInput.values
        val result = IntArray(OUTPUT_W * OUTPUT_H)
        var dest = 0
        for (y in 0 until OUTPUT_H) {
            for (x in 0 until OUTPUT_W) {
                val denom = m[6]*x + m[7]*y + m[8]
                if (kotlin.math.abs(denom) < 1e-10) {
                    result[dest++] = 0xff000000.toInt()
                    continue
                }
                val sx = (m[0]*x + m[1]*y + m[2]) / denom
                val sy = (m[3]*x + m[4]*y + m[5]) / denom
                if (!sx.isFinite() || !sy.isFinite() ||
                    sx < 0.0 || sy < 0.0 || sx > width-1 || sy > height-1) {
                    result[dest++] = 0xff000000.toInt()
                    continue
                }
                val x0=floor(sx).toInt()
                val y0=floor(sy).toInt()
                val x1=(x0+1).coerceAtMost(width-1)
                val y1=(y0+1).coerceAtMost(height-1)
                val dx=sx-x0
                val dy=sy-y0
                val a=argb[y0*width+x0]
                val b=argb[y0*width+x1]
                val c=argb[y1*width+x0]
                val d=argb[y1*width+x1]
                fun ch(shift:Int):Int {
                    val c00=(a ushr shift) and 255
                    val c01=(b ushr shift) and 255
                    val c10=(c ushr shift) and 255
                    val c11=(d ushr shift) and 255
                    val top=c00*(1.0-dx)+c01*dx
                    val bottom=c10*(1.0-dx)+c11*dx
                    return (top*(1.0-dy)+bottom*dy).toInt().coerceIn(0,255)
                }
                result[dest++] = 0xff000000.toInt() or
                    (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return result
    }

    /** Does not imply geometrical calibration or model accuracy. */
    fun pairToModelInput(
        previous: IntArray,
        current: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        outputToInput: Matrix
    ): FloatArray {
        val a=warp(previous,sourceWidth,sourceHeight,outputToInput)
        val b=warp(current,sourceWidth,sourceHeight,outputToInput)
        return OpenpilotYuvPacker.packPair(a,b)
    }
}
