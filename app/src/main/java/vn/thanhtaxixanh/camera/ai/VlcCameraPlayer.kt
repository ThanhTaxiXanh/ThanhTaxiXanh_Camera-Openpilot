package vn.thanhtaxixanh.camera.ai

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

data class VlcFrameStats(
    val decoded: Long,
    val displayed: Long,
    val lost: Long,
    val bitrateBitsPerSec: Float
)

/** Alternative RTSP playback backend for cameras using LIVE555 dynamic Content-Base. */
class VlcCameraPlayer(
    private val context: Context,
    private val view: VLCVideoLayout,
    private val cacheMs: Int,
    private val callback: (String, Boolean) -> Unit
) {
    private var vlc: LibVLC? = null
    private var player: MediaPlayer? = null

    fun play(url: String) {
        close()
        val safeCache = cacheMs.coerceIn(200, 1800)
        val lib = LibVLC(context, arrayListOf(
            "--rtsp-tcp", "--no-audio", "--network-caching=$safeCache",
            "--drop-late-frames", "--skip-frames"
        ))
        vlc = lib
        val p = MediaPlayer(lib)
        player = p
        p.attachViews(view, null, false, false)
        p.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> callback("VLC: bắt đầu kết nối", false)
                MediaPlayer.Event.Buffering -> callback("VLC: đang đệm video", false)
                MediaPlayer.Event.Playing -> callback("VLC: phát RTSP, chờ khung hình", false)
                MediaPlayer.Event.Vout -> callback("VLC: khung hình đã xuất hiện", true)
                MediaPlayer.Event.EncounteredError -> callback("VLC: lỗi phiên RTSP", false)
                MediaPlayer.Event.EndReached -> callback("VLC: hết luồng video", false)
            }
        }
        val media = Media(lib, Uri.parse(url))
        try {
            media.setHWDecoderEnabled(true, false)
            media.addOption(":rtsp-tcp")
            media.addOption(":network-caching=$safeCache")
            p.setMedia(media)
        } finally {
            media.release()
        }
        p.play()
    }

    /**
     * Native decoder counters. Not the screen-recorder FPS and not AI FPS.
     * getMedia() provides a retained reference; caller must release it.
     */
    fun currentFrameStats(): VlcFrameStats? {
        val current = player ?: return null
        val media = try { current.media } catch (_: Exception) { null } ?: return null
        return try {
            val counters = media.stats ?: return null
            VlcFrameStats(
                decoded = counters.decodedVideo.toLong(),
                displayed = counters.displayedPictures.toLong(),
                lost = counters.lostPictures.toLong(),
                bitrateBitsPerSec = counters.inputBitrate
            )
        } catch (_: Exception) {
            null
        } finally {
            media.release()
        }
    }

    fun close() {
        val p = player
        player = null
        if (p != null) {
            try { p.stop() } catch (_: Exception) {}
            try { p.detachViews() } catch (_: Exception) {}
            try { p.release() } catch (_: Exception) {}
        }
        val lib = vlc
        vlc = null
        if (lib != null) try { lib.release() } catch (_: Exception) {}
    }
}
