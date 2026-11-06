"""Static guard: source field evidence is wired into the A500S IJK A/B UI.

This is NOT real 70mai video decoding, synchronized optical latency,
RTSP camera-session provenance, or hardware freeze detection.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/java/vn/thanhtaxixanh/camera/ai"
MAIN = (BASE / "MainActivity.kt").read_text()
P = (BASE / "IjkPlaybackProfile.kt").read_text()
TRACE = (BASE / "FiveMinuteStreamEvidence.kt").read_text()


class A500sIJKFieldLagGuard(unittest.TestCase):
    def test_only_opt_in_tcp_profiles_have_cache_bounds(self):
        for label in ('SMOOTH("MƯỢT TCP"', 'BALANCED("CÂN BẰNG TCP"',
                      'LOW_LATENCY("TRỄ THẤP TCP"', 'UDP_SMOOTH("THỬ UDP'):
            self.assertIn(label, P)
        self.assertIn('maxCachedDurationMs = 1000L', P)
        self.assertIn('maxDelayUs = 500_000L', P)
        self.assertIn('maxCachedDurationMs = 600L', P)
        self.assertIn('maxDelayUs = 300_000L', P)
        self.assertIn('SMOOTH("MƯỢT TCP", "IJK: MƯỢT TCP · ĐỆM MẶC ĐỊNH", "tcp", 1L, false)', P)
        self.assertIn('"udp", 1L, false);', P)

    def test_native_options_are_only_set_at_ijk_session_start(self):
        start = MAIN[MAIN.index('private fun startIjk('):
                     MAIN.index('private fun recoverIjk(')]
        self.assertIn('activeIjkProfile.minFrames?.let', start)
        self.assertIn('activeIjkProfile.maxCachedDurationMs?.let', start)
        self.assertIn('activeIjkProfile.maxDelayUs?.let', start)
        self.assertNotIn('"fflags", "nobuffer"', start)
        self.assertNotIn('startDirect(', start)
        self.assertIn('player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)', start)

    def test_ijk_null_during_reconnect_is_recorded_not_sealed(self):
        ticker = MAIN[MAIN.index('private val statusTicker ='):
                      MAIN.index('@Deprecated("Android Storage Access Framework result callback")')]
        self.assertIn('Backend.IJK -> {', ticker)
        self.assertIn('reconnecting = ijkRecoveryPending || player == null', ticker)
        self.assertIn('ijkOutputFps = fps, ijkPlaying = playing', ticker)
        self.assertIn('if (item == null) audit.stop(clock, "PLAYER_UNAVAILABLE")', ticker)
        self.assertIn('activeLiveTrace?.reconnect(SystemClock.elapsedRealtime(), cause)', MAIN)
        self.assertIn('RTSP_ON_COMPLETION', MAIN)

    def test_reconnect_is_always_a_fail_even_after_recovery(self):
        self.assertIn('problem("RECONNECT_OBSERVED")', TRACE)
        self.assertIn('if (moving) {', TRACE)
        self.assertIn('event:local_progress_resumed', TRACE)
        self.assertIn('max_local_recovery_gap_ms=', TRACE)
        self.assertIn('val localPass = reason == "DURATION_REACHED" &&', TRACE)
        self.assertIn('faults.isEmpty()', TRACE)

if __name__ == "__main__":
    unittest.main()
