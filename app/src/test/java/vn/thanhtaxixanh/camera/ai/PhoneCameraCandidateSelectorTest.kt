package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * Camera2 selection-policy JVM fixtures. These are virtual camera listings,
 * not proof of any specific handset's ImageReader/Surface operation.
 */
class PhoneCameraCandidateSelectorTest {
    private val S = PhoneCameraCandidateSelector
    private val BACK = PhoneCameraCandidateSelector.Facing.BACK
    private val FRONT = PhoneCameraCandidateSelector.Facing.FRONT
    private val OTHER = PhoneCameraCandidateSelector.Facing.OTHER

    private fun c(
        id: String,
        facing: PhoneCameraCandidateSelector.Facing,
        supported: Boolean = true,
        realtime: Boolean = true
    ) = PhoneCameraCandidateSelector.Candidate(id, facing, supported, realtime)

    @Test fun firstRearLensWithNoYuvDoesNotHideUsableRear() {
        val entries = listOf(c("ultra",BACK,false),c("wide",BACK,true),
            c("selfie",FRONT,true))
        assertEquals("wide", S.select(entries)?.id)
    }

    @Test fun realtimeRearPreferredOverUnknownClockRear() {
        val entries = listOf(c("rear-unknown",BACK,true,false),
            c("rear-realtime",BACK,true,true))
        assertEquals("rear-realtime", S.select(entries)?.id)
    }

    @Test fun compatibleBackUnknownClockBeatsFrontRealtime() {
        val entries = listOf(c("front",FRONT,true,true),
            c("rear",BACK,true,false))
        assertEquals("rear", S.select(entries)?.id)
        assertFalse(S.select(entries)!!.realtimeSensorClock)
        // The existing RAW inference guard rejects UNKNOWN timebase.
    }

    @Test fun frontUsedOnlyWhenNoSupportedBackLens() {
        val entries = listOf(c("rear",BACK,false,true),
            c("front-unknown",FRONT,true,false),
            c("front-real",FRONT,true,true))
        assertEquals("front-real", S.select(entries)?.id)
    }

    @Test fun anotherFacingOnlyAfterAllBackAndFrontUnavailable() {
        val entries = listOf(c("front",FRONT,false),
            c("other",OTHER,true,true),
            c("rear",BACK,false))
        assertEquals("other", S.select(entries)?.id)
    }

    @Test fun noAdvertisedYuvSizeMustFailClosed() {
        assertNull(S.select(emptyList()))
        assertNull(S.select(listOf(c("0",BACK,false),c("1",FRONT,false))))
    }

    @Test fun stableTieUsesSystemCameraOrderRatherThanImplicitIdOrdering() {
        val entries = listOf(c("20",BACK,true,true),
            c("2",BACK,true,true),c("1",FRONT,true,true))
        assertEquals("20", S.select(entries)?.id)
    }

    @Test fun blankCameraIdsIgnoredEvenIfRankedFirst() {
        val entries = listOf(c(" ",BACK,true,true),
            c("0",BACK,true,false),c("",BACK,true,true))
        assertEquals("0", S.select(entries)?.id)
    }

    @Test fun surveyCanSkipUnavailableAuxiliaryCameraWithoutRejectingOthers() {
        // CameraAccessException / invalid StreamConfigurationMap are filtered
        // by the Android adapter before the pure selector is called.
        val afterSurvey = listOf(c("aux-unusable",BACK,false,true),
            c("real-back",BACK,true,false),c("front",FRONT,true,true))
        assertEquals("real-back", S.select(afterSurvey)?.id)
    }

    @Test fun noUnexpectedMutationOfCameraDescriptions() {
        val entries = listOf(c("rear",BACK,true,false),c("front",FRONT,true))
        val copy = entries.toList()
        val value = S.select(entries)
        assertEquals("rear", value?.id)
        assertEquals(copy, entries)
    }
}
