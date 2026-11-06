package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** Synthetic IJK native metadata; neither real H264 nor optical evidence. */
class IjkPlaybackHealthGateTest {
    @Test fun startWithoutSustainedFpsTimesOutEvenIfOneFrameInfoWasReported() {
        val gate = IjkPlaybackHealthGate()
        gate.reset(1_000L)
        assertEquals(IjkPlaybackHealthGate.Verdict.WAITING,
            gate.sample(12_000L, 0f, false))
        assertEquals(IjkPlaybackHealthGate.Verdict.START_TIMEOUT,
            gate.sample(13_001L, 0f, true))
    }

    @Test fun stalePositiveNativeFpsCannotHideStoppedPlayer() {
        val gate = IjkPlaybackHealthGate()
        gate.reset(0L)
        assertEquals(IjkPlaybackHealthGate.Verdict.HEALTHY,
            gate.sample(1_000L, 24f, true))
        assertEquals(IjkPlaybackHealthGate.Verdict.WAITING,
            gate.sample(7_000L, 24f, false))
        assertEquals(IjkPlaybackHealthGate.Verdict.STALLED,
            gate.sample(8_000L, 24f, false))
    }

    @Test fun validPlayingFpsResetsRecoveryCountdown() {
        val gate = IjkPlaybackHealthGate()
        gate.reset(0L)
        gate.sample(1_000L, 21f, true)
        assertEquals(IjkPlaybackHealthGate.Verdict.WAITING,
            gate.sample(7_000L, 0f, true))
        assertEquals(IjkPlaybackHealthGate.Verdict.HEALTHY,
            gate.sample(7_200L, 22f, true))
        assertEquals(IjkPlaybackHealthGate.Verdict.WAITING,
            gate.sample(13_000L, 0f, true))
    }

    @Test fun sustainedLowFpsAfterHealthyPlaybackRecovers() {
        val gate = IjkPlaybackHealthGate()
        gate.reset(0L)
        gate.sample(1_000L, 20f, true)
        assertEquals(IjkPlaybackHealthGate.Verdict.WAITING,
            gate.sample(7_000L, 1.5f, true))
        assertEquals(IjkPlaybackHealthGate.Verdict.STALLED,
            gate.sample(8_000L, 0f, true))
    }

    @Test fun nanAndInfinityAreNotHealthySamples() {
        val gate = IjkPlaybackHealthGate(startupTimeoutMs = 5_000L)
        gate.reset(0L)
        gate.sample(1_000L, Float.NaN, true)
        assertEquals(IjkPlaybackHealthGate.Verdict.START_TIMEOUT,
            gate.sample(5_001L, Float.POSITIVE_INFINITY, true))
    }

    @Test fun resetCannotInheritEarlierHealthyFpsOrRecoveryDeadline() {
        val gate = IjkPlaybackHealthGate()
        gate.reset(0L)
        gate.sample(1_000L, 20f, true)
        gate.sample(7_000L, 0f, false)
        gate.reset(10_000L)
        assertEquals(IjkPlaybackHealthGate.Verdict.WAITING,
            gate.sample(11_000L, 0f, false))
        assertEquals(IjkPlaybackHealthGate.Verdict.START_TIMEOUT,
            gate.sample(22_001L, 0f, false))
    }

    @Test fun backwardOrDuplicateSampleCannotEstablishHealth() {
        val gate = IjkPlaybackHealthGate()
        gate.reset(10_000L)
        assertEquals(IjkPlaybackHealthGate.Verdict.WAITING,
            gate.sample(9_999L, 25f, true))
        assertEquals(IjkPlaybackHealthGate.Verdict.WAITING,
            gate.sample(10_000L, 25f, true))
        assertEquals(IjkPlaybackHealthGate.Verdict.START_TIMEOUT,
            gate.sample(22_001L, 0f, false))
    }
}
