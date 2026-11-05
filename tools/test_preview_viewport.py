"""Static safety audit for SurfaceView persistent layout structure.

A physical Android screen test is still REQUIRED. This test prevents
regression of the source-level 0-height weighted SurfaceView bug proven
by the owner's 2026-11-05 screen recording.
"""
import unittest
from pathlib import Path

SOURCE = (Path(__file__).resolve().parents[1] / "app/src/main/java" /
          "vn/thanhtaxixanh/camera/ai/MainActivity.kt").read_text()


class PreviewViewportStructure(unittest.TestCase):
    def test_surface_stays_in_stable_frame_not_inside_scroll_controls(self):
        self.assertIn("outer.addView(cameraVideoContainer", SOURCE)
        self.assertIn("cameraControlsScroll = ScrollView(this)", SOURCE)
        self.assertIn("outer.addView(cameraControlsScroll", SOURCE)
        self.assertIn("setContentView(outer)", SOURCE)
        self.assertIn("normalPreviewHeightPx = PreviewViewportPolicy.heightPx(", SOURCE)
        self.assertIn("MATCH_PARENT, normalPreviewHeightPx", SOURCE)
        self.assertNotIn("root.addView(videoContainer, LinearLayout.LayoutParams(", SOURCE)

    def test_surface_and_hud_shared_only_inside_video_frame(self):
        self.assertIn("cameraVideoContainer.addView(adasHud,", SOURCE)
        self.assertIn("addView(surfaceView, FrameLayout.LayoutParams(-1, -1))", SOURCE)
        self.assertIn("background = null", SOURCE)
        self.assertIn("holder.setFormat(PixelFormat.OPAQUE)", SOURCE)

    def test_pip_uses_full_window_and_restores_nonzero_height(self):
        self.assertIn("child !== cameraVideoContainer", SOURCE)
        self.assertIn("if (isInPictureInPictureMode) 1f else 0f", SOURCE)
        self.assertIn("if (isInPictureInPictureMode) 0 else normalPreviewHeightPx", SOURCE)


if __name__ == "__main__":
    unittest.main()
