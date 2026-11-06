package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * Policy-only tests. No simulated software codec is treated as genuine
 * device output, and these must not promote criterion #14 or Gate B.
 */
class OffscreenCodecSelectionTest {
    private fun c(name: String, avc: Boolean = true, software: Boolean = true) =
        OffscreenCodecSelection.Candidate(name, avc, software)

    @Test fun choosesOnlySoftwareAvcToAvoidTwoHardwareInstances() {
        val names = OffscreenCodecSelection.softwareAvcCandidates(listOf(
            c("c2.vendor.avc.decoder",software=false),
            c("c2.android.avc.decoder"),
            c("c2.android.hevc.decoder",avc=false),
            c("OMX.google.h264.decoder")))
        assertEquals(listOf("c2.android.avc.decoder","OMX.google.h264.decoder"),names)
    }

    @Test fun emptySoftwareCandidateListAllowsSystemDefaultFallback() {
        val names=OffscreenCodecSelection.softwareAvcCandidates(listOf(
            c("vendor.avc",software=false),c("system.hevc",avc=false)))
        assertTrue(names.isEmpty())
    }

    @Test fun duplicateCodecAliasesDoNotCauseRepeatedConfigureAttempts() {
        val names=OffscreenCodecSelection.softwareAvcCandidates(listOf(
            c("c2.android.avc.decoder"),c("c2.android.avc.decoder"),
            c("OMX.google.avc"),c("OMX.google.avc")))
        assertEquals(listOf("c2.android.avc.decoder","OMX.google.avc"),names)
    }

    @Test fun stableHardwareEnumerationPriorityPreserved() {
        val names=OffscreenCodecSelection.softwareAvcCandidates(listOf(
            c("software-B"),c("vendor",software=false),c("software-A")))
        assertEquals(listOf("software-B","software-A"),names)
    }

    @Test fun blankCodecNamesAreNotSelected() {
        val names=OffscreenCodecSelection.softwareAvcCandidates(listOf(
            c(" "),c(""),c("software-avc")))
        assertEquals(listOf("software-avc"),names)
    }

    @Test fun firstAndSecondInputMissesTriggerOutputDrainRetries() {
        assertEquals(OffscreenCodecSelection.InputWaitVerdict.RETRY_DRAIN,
            OffscreenCodecSelection.inputBufferUnavailable(1))
        assertEquals(OffscreenCodecSelection.InputWaitVerdict.RETRY_DRAIN,
            OffscreenCodecSelection.inputBufferUnavailable(2))
    }

    @Test fun thirdInputMissResyncsDependentGopOnly() {
        assertEquals(OffscreenCodecSelection.InputWaitVerdict.RESYNC_IDR,
            OffscreenCodecSelection.inputBufferUnavailable(3))
        assertEquals(OffscreenCodecSelection.InputWaitVerdict.RESYNC_IDR,
            OffscreenCodecSelection.inputBufferUnavailable(2,2))
    }

    @Test fun invalidWaitLimitsFailClosed() {
        for (attempt in listOf(0,4)) {
            try {
                OffscreenCodecSelection.inputBufferUnavailable(attempt)
                fail("invalid attempt "+attempt)
            } catch (_: IllegalArgumentException) {}
        }
        for (limit in listOf(0,9)) {
            try {
                OffscreenCodecSelection.inputBufferUnavailable(1,limit)
                fail("invalid limit "+limit)
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun noHardwareOnlyDecoderMayBeDeclaredSoftwareByPolicy() {
        val names=OffscreenCodecSelection.softwareAvcCandidates(listOf(
            c("vendor.avc",software=false),
            c("vendor.hevc",avc=false,software=false)))
        assertFalse(names.contains("vendor.avc"))
    }

    @Test fun softwareDecoderHasBoundedLongerInputBudgetThanHardware() {
        val sw=OffscreenCodecSelection.inputWaitPlan(true)
        val hw=OffscreenCodecSelection.inputWaitPlan(false)
        assertEquals(8,sw.maxAttempts)
        assertEquals(20_000L,sw.timeoutUs)
        assertEquals(3,hw.maxAttempts)
        assertEquals(10_000L,hw.timeoutUs)
        assertTrue(sw.maxAttempts*sw.timeoutUs<=200_000L)
        assertTrue(hw.maxAttempts*hw.timeoutUs<=200_000L)
    }

    @Test fun softwareOnlyResyncsAfterEightExhaustedInputWaits() {
        val sw=OffscreenCodecSelection.inputWaitPlan(true)
        for (n in 1 until sw.maxAttempts) {
            assertEquals(OffscreenCodecSelection.InputWaitVerdict.RETRY_DRAIN,
                OffscreenCodecSelection.inputBufferUnavailable(n,sw.maxAttempts))
        }
        assertEquals(OffscreenCodecSelection.InputWaitVerdict.RESYNC_IDR,
            OffscreenCodecSelection.inputBufferUnavailable(8,8))
    }

    @Test fun hardwareBudgetStillExhaustsAfterThreeInputWaits() {
        val hw=OffscreenCodecSelection.inputWaitPlan(false)
        assertEquals(OffscreenCodecSelection.InputWaitVerdict.RESYNC_IDR,
            OffscreenCodecSelection.inputBufferUnavailable(hw.maxAttempts,
                hw.maxAttempts))
    }

    @Test fun invalidNativeWaitPlanCannotExtendInputStarvationIndefinitely() {
        val invalid=listOf(
            Pair(0,10_000L),Pair(9,10_000L),Pair(8,30_000L),
            Pair(8,0L),Pair(1,-1L),Pair(8,25_001L))
        for ((attempts,us) in invalid) {
            try {
                OffscreenCodecSelection.InputWaitPlan(attempts,us)
                fail("Accepted unbounded input wait policy: $attempts/$us")
            } catch (_:IllegalArgumentException) {}
        }
    }

    @Test fun softwareFirstRemainsSafeDefaultForExistingCallers() {
        assertTrue(OffscreenCodecSelection.shouldProbeSoftware(
            OffscreenCodecSelection.Preference.SOFTWARE_FIRST))
    }

    @Test fun systemDefaultDoesNotProbeSoftwareCandidatesBeforeNativeSelect() {
        assertFalse(OffscreenCodecSelection.shouldProbeSoftware(
            OffscreenCodecSelection.Preference.PLATFORM_DEFAULT))
    }

    @Test fun preferenceDoesNotChangeOutputFreshnessOrInputClockPolicy() {
        val gate=OffscreenYuvOutputGate()
        for (choice in OffscreenCodecSelection.Preference.values()) {
            assertEquals(OffscreenYuvOutputGate.Verdict.STALE_FRAME,
                gate.check(1L,1L,true,false,1000L,1600L,-1L))
            assertEquals(OffscreenYuvRejectionPolicy.Action.DROP_AND_INVALIDATE_TEMPORAL,
                OffscreenYuvRejectionPolicy.action(
                    gate.check(1L,1L,true,false,1000L,1600L,-1L)))
            assertEquals(OffscreenGopAgeGate.Age.STALE,
                OffscreenGopAgeGate().age(1000L,1600L))
        }
    }

    @Test fun nativeDefaultSelectionLabelMayAlsoBeSoftwareInAndroid() {
        // Absence of an explicit codec name is NOT a hardware guarantee.
        assertFalse(OffscreenCodecSelection.shouldProbeSoftware(
            OffscreenCodecSelection.Preference.PLATFORM_DEFAULT))
        assertTrue(OffscreenCodecSelection.softwareAvcCandidates(listOf(
            c("c2.android.avc.decoder"))).isNotEmpty())
    }
}
