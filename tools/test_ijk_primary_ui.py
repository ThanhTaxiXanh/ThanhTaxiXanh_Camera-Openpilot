"""Regression for the user's explicit 2026-11-06 IJK-first decision.

This enforces UI/Java control flow, not decoded-video correctness or
sub-second optical delay. The licensed 70mai 4.5.0 SDK is not included.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/java/vn/thanhtaxixanh/camera/ai"
MAIN = (BASE / "MainActivity.kt").read_text()
PROFILE = (BASE / "IjkPlaybackProfile.kt").read_text()


class IjkFirstA500sUI(unittest.TestCase):
    def test_ijk_live_action_is_always_visible_outside_scrolling_controls(self):
        a = MAIN.index('ijkButton = Button(this).apply')
        b = MAIN.index('val navigation =', a)
        startup = MAIN[a:b]
        self.assertIn("▶ PHÁT LIVE 70MAI · IJK/FFMPEG", startup)
        self.assertIn("begin(Backend.IJK)", startup)
        self.assertIn("outer.addView(ijkButton", startup)
        self.assertNotIn("root.addView(ijkButton)", startup)
        self.assertIn("outer.addView(cameraControlsScroll", MAIN)
        self.assertIn("setContentView(outer)", MAIN)

    def test_direct_and_secondary_yuv_controls_are_collapsed_by_default(self):
        self.assertIn("val directAdvancedControls = LinearLayout(this).apply", MAIN)
        start = MAIN.index("val directAdvancedControls = LinearLayout(this).apply")
        self.assertIn("visibility = View.GONE", MAIN[start:start+170])
        self.assertIn("root.addView(advancedToggle)", MAIN)
        self.assertIn("root.addView(directAdvancedControls)", MAIN)
        for name in ("directButton", "directLatencyButton", "yuvTapButton",
                     "yuvCodecButton", "rawAiButton", "yuvCaptureButton",
                     "yuvSnapshotButton"):
            self.assertIn(f"directAdvancedControls.addView({name})", MAIN)
            self.assertNotIn(f"root.addView({name})", MAIN)

    def test_experiment_is_explicit_no_direct_auto_fallback(self):
        self.assertIn('text = "THỬ NGHIỆM: xADAS · DIRECT MEDIACODEC"', MAIN)
        self.assertIn("setOnClickListener { begin(Backend.DIRECT) }", MAIN)
        self.assertIn("Backend.IJK -> startIjk(generation)", MAIN)
        self.assertIn("Backend.DIRECT -> startDirect(generation)", MAIN)
        # IJK recovery goes back to its own backend only.
        recover = MAIN[MAIN.index("private fun recoverIjk("):
                       MAIN.index("private fun releaseIjk(")]
        self.assertIn("startIjk(token)", recover)
        self.assertNotIn("startDirect(", recover)
        self.assertIn("if (token != generation || backend != Backend.IJK", recover)

    def test_current_smooth_ijk_transport_and_watchdog_remain_unchanged(self):
        self.assertIn('SMOOTH("MƯỢT TCP"', PROFILE)
        self.assertIn('LOW_LATENCY("TRỄ THẤP TCP"', PROFILE)
        self.assertIn('UDP_SMOOTH("THỬ UDP', PROFILE)
        self.assertIn("private var ijkProfile = IjkPlaybackProfile.SMOOTH", MAIN)
        self.assertIn('player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)', MAIN)
        self.assertIn("IjkPlaybackHealthGate()", MAIN)
        self.assertIn("ijkPlaybackHealth.sample(now, outputFps, nativePlaying)", MAIN)
        self.assertIn("IjkPlaybackHealthGate.Verdict.START_TIMEOUT", MAIN)
        self.assertIn("IjkPlaybackHealthGate.Verdict.STALLED", MAIN)
        self.assertIn('recoverIjk(generation,', MAIN)

if __name__ == "__main__":
    unittest.main()
