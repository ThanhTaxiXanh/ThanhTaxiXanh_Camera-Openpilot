package vn.thanhtaxixanh.camera.ai

/**
 * A/B configurations for physical A500S latency and continuity measurement.
 *
 * SMOOTH and UDP_SMOOTH preserve earlier native player options exactly.
 * BALANCED caps internal IJK cache while retaining packet buffering, unlike
 * LOW_LATENCY which disables packet buffering entirely. The parameter values
 * are experimental bounds, NOT measured optical camera-to-display latency.
 */
enum class IjkPlaybackProfile(
    val shortLabel: String,
    val buttonTitle: String,
    val rtspTransport: String,
    val packetBuffering: Long,
    val experimentalLowLatency: Boolean,
    val minFrames: Long? = null,
    val maxCachedDurationMs: Long? = null,
    val maxDelayUs: Long? = null
) {
    SMOOTH("MƯỢT TCP", "IJK: MƯỢT TCP · ĐỆM MẶC ĐỊNH", "tcp", 1L, false),
    BALANCED("CÂN BẰNG TCP", "IJK: CÂN BẰNG TCP · THỬ GIẢM ĐỆM", "tcp",
        1L, false, minFrames = 10L, maxCachedDurationMs = 1000L,
        maxDelayUs = 500_000L),
    LOW_LATENCY("TRỄ THẤP TCP", "IJK: TRỄ THẤP TCP · CÓ THỂ GIẬT", "tcp",
        0L, true, minFrames = 5L, maxCachedDurationMs = 600L,
        maxDelayUs = 300_000L),
    UDP_SMOOTH("THỬ UDP / GIỮ ĐỆM", "IJK: THỬ RTP/UDP · GIỮ ĐỆM MƯỢT",
        "udp", 1L, false);

    init {
        require(packetBuffering in 0L..1L)
        require(minFrames == null || minFrames in 5L..500L)
        require(maxCachedDurationMs == null ||
            maxCachedDurationMs in 100L..10_000L)
        require(maxDelayUs == null || maxDelayUs in 0L..10_000_000L)
        require((minFrames == null) == (maxCachedDurationMs == null))
        require((maxCachedDurationMs == null) == (maxDelayUs == null))
    }

    /** Apply only when a brand-new player is allocated, not while playing. */
    fun next(): IjkPlaybackProfile = values()[(ordinal + 1) % values().size]
}
