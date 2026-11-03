package vn.thanhtaxixanh.camera.ai

/**
 * Experimental IJK playback selection for a live 70mai A500S feed.
 * SMOOTH keeps the existing verified player settings unchanged.
 *
 * LOW_LATENCY reduces only the application's playback buffer. Actual
 * camera-to-phone delay must be measured with a real-world visual event;
 * getVideoCachedDuration does not measure sensor/encoder/Wi-Fi delay.
 */
enum class IjkPlaybackProfile(
    val shortLabel: String,
    val buttonTitle: String,
    val packetBuffering: Long
) {
    SMOOTH("MƯỢT", "IJK: MƯỢT · ĐỆM MẶC ĐỊNH", 1L),
    LOW_LATENCY("THỬ TRỄ THẤP", "IJK: THỬ TRỄ THẤP · CÓ THỂ GIẬT", 0L);

    fun next() = if (this == SMOOTH) LOW_LATENCY else SMOOTH
}
