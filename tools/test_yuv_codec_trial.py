"""CI source integration guard for explicit secondary AVC codec trial.

This is not physical proof. It prevents accidental changes to primary
Surface decoder selection or user preference mutation during playback.
"""
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
PKG=ROOT/"app/src/main/java/vn/thanhtaxixanh/camera/ai"
MAIN=(PKG/"MainActivity.kt").read_text()
DIRECT=(PKG/"DirectRtspPlayer.kt").read_text()
SIDECAR=(PKG/"H264YuvSidecar.kt").read_text()

class OwnerSecondaryCodecTrial(unittest.TestCase):
    def test_ui_preference_locked_to_new_session(self):
        a=MAIN.index("yuvCodecButton = Button(this).apply")
        b=MAIN.index("root.addView(yuvCodecButton)")
        controls=MAIN[a:b]
        self.assertIn("backend != null",controls)
        self.assertIn("return@setOnClickListener",controls)
        self.assertIn("Preference.PLATFORM_DEFAULT",controls)
        self.assertIn("Preference.SOFTWARE_FIRST",controls)
        self.assertIn("activeDirectLatencyPolicy,\n            yuvCodecPreference",MAIN)

    def test_selection_reaches_only_optional_yuv_sidecar(self):
        self.assertIn("private val yuvPreference: OffscreenCodecSelection.Preference",DIRECT)
        self.assertIn("decoderPreference = yuvPreference",DIRECT)
        self.assertIn("private val decoderPreference: OffscreenCodecSelection.Preference",SIDECAR)
        self.assertIn("shouldProbeSoftware(decoderPreference)",SIDECAR)
        self.assertIn('MediaCodec.createDecoderByType("video/avc")',SIDECAR)

    def test_primary_media_codec_not_switched_to_secondary_selection(self):
        # Main output Surface must keep its own standard AVC codec.
        primary='val decoder=MediaCodec.createDecoderByType("video/avc")'
        self.assertIn(primary,DIRECT)
        region=DIRECT[DIRECT.index(primary):DIRECT.index(primary)+400]
        self.assertNotIn("yuvPreference",region)
        self.assertNotIn("decoderPreference",region)
        self.assertNotIn("decoderPreference",MAIN)
        self.assertIn("frameConsumer",DIRECT)

if __name__=="__main__":
    unittest.main()
