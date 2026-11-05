package vn.thanhtaxixanh.camera.ai

/**
 * Layout policy for the persistent A500S video viewport on compact Android
 * phones. Controls are independently scrollable; they must NEVER consume the
 * weighted height of the SurfaceView/Direct MediaCodec video layer.
 *
 * Pixel dimensions are screen-local, not H264 or calibrated camera geometry.
 */
object PreviewViewportPolicy {
    fun heightPx(contentWidthPx: Int, screenHeightPx: Int, density: Float): Int {
        require(contentWidthPx > 0 && screenHeightPx > 0)
        require(density.isFinite() && density > 0f)
        // 4:3 is the known A500S preview aspect (640x480). Other sources
        // can letterbox without altering or resampling compressed frames.
        val preferred = (contentWidthPx * 3L / 4L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val maximum = (screenHeightPx * 45L / 100L)
            .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val minimum = (160f * density).toLong()
            .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        return preferred.coerceAtMost(maximum).coerceAtLeast(
            minimum.coerceAtMost(maximum)
        )
    }
}
