"""Static Android source-wiring checks for an opt-in IJK Surface readback.

These do not demonstrate real PixelCopy availability, image freshness,
model-use rights, trained driving inference or optical camera latency.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/java/vn/thanhtaxixanh/camera/ai"
MAIN = (BASE / "MainActivity.kt").read_text()
TAP = (BASE / "IjkSurfaceFrameTap.kt").read_text()


class IjkPixelCopyWiring(unittest.TestCase):
    def test_no_second_h264_decoder_is_created_for_ijk_tap(self):
        self.assertIn("PixelCopy.request(video, image", TAP)
        self.assertIn("callbackThread = HandlerThread(", TAP)
        self.assertNotIn("MediaCodec", TAP)
        self.assertNotIn("IjkMediaPlayer()", TAP)
        self.assertNotIn("MediaCodec.createDecoderByType", MAIN[
            MAIN.index("private fun restartIjkSurfaceTap("):
            MAIN.index("private fun stopRawVision()")])

    def test_raw_ijk_remains_owner_opt_in_in_collapsed_advanced_panel(self):
        self.assertIn('ijkRawAiButton = Button(this).apply', MAIN)
        self.assertIn('setOnClickListener { toggleA500sRawVision(Backend.IJK) }', MAIN)
        self.assertIn('directAdvancedControls.addView(ijkRawAiButton)', MAIN)
        self.assertIn('visibility = View.GONE', MAIN[
            MAIN.index('val directAdvancedControls ='):MAIN.index('directButton = Button')])
        self.assertNotIn('restartIjkSurfaceTap()', MAIN[
            MAIN.index('setOnClickListener { begin(Backend.IJK) }'):
            MAIN.index('directButton = Button')])

    def test_surface_copy_is_bounded_and_shutdown_revokes_queued_callbacks(self):
        self.assertIn('gate.request(', TAP)
        self.assertIn('gate.complete(ticket,', TAP)
        self.assertIn('if (!closed) ui.postDelayed(this, 350L)', TAP)
        self.assertIn('gate.stop()', TAP)
        self.assertIn('callbackThread.quitSafely()', TAP)
        self.assertIn('rawVision?.invalidate()', MAIN)
        self.assertIn('suspendIjkSurfaceTap()', MAIN[
            MAIN.index('private fun recoverIjk('):
            MAIN.index('private fun releaseIjk(')])

    def test_no_result_is_promoted_to_hud_or_distance(self):
        block = MAIN[MAIN.index("private fun toggleA500sRawVision("):
                     MAIN.index("private fun startDirect(")]
        self.assertIn("calibration = null", block)
        self.assertIn("IjkArgbVisionBridge(", block)
        self.assertIn("sourceWidth = A500sRawVisionBridge.EXPECTED_WIDTH", block)
        for dangerous in ("adasHud.setLead(", "adasHud.showLanes(", "warningEngine.play("):
            self.assertNotIn(dangerous, block)


if __name__ == "__main__":
    unittest.main()
