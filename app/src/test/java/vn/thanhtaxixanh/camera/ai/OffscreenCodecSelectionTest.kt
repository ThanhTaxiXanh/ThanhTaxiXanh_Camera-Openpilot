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
}
