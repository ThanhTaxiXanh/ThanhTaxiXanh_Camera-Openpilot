package vn.thanhtaxixanh.camera.ai

/** Isolated A/B: UDP_SMOOTH changes only FFmpeg RTSP transport vs SMOOTH. */
enum class IjkPlaybackProfile(
    val shortLabel: String,
    val buttonTitle: String,
    val rtspTransport: String,
    val packetBuffering: Long,
    val experimentalLowLatency: Boolean
) {
    SMOOTH("MƯỢT TCP", "IJK: MƯỢT TCP · ĐỆM MẶC ĐỊNH", "tcp", 1L, false),
    LOW_LATENCY("TRỄ THẤP TCP", "IJK: TRỄ THẤP TCP · CÓ THỂ GIẬT", "tcp", 0L, true),
    UDP_SMOOTH("THỬ UDP / GIỮ ĐỆM", "IJK: THỬ RTP/UDP · GIỮ ĐỆM MƯỢT", "udp", 1L, false);

    fun next(): IjkPlaybackProfile = values()[(ordinal + 1) % values().size]
}
