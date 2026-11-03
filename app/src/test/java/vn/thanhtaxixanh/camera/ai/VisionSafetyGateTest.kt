package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class VisionSafetyGateTest {
    private val calibrated = VisionSafetyGate.Calibration(
        1.5f, 100f, -2f, 0f, "reference-2026-a500s",
        groundTruthValidated = true,
        modelCoordinateFrameValidated = true
    )
    private fun data(time:Long = 1_000L) = VisionSafetyGate.TimedResult(
        time,time+25,0.92f,0.88f,0.95f,20f,5f,true
    )

    @Test fun noModelShowsNoLanesOrDistance() {
        val s = VisionSafetyGate.assess(null, calibrated,1100,12f)
        assertEquals(VisionSafetyGate.DataStatus.NO_MODEL,s.status)
        assertFalse(s.mayWarn)
        assertNull(s.leadDistanceMeters)
    }

    @Test fun userEnteredGeometryAloneCannotEnableWarnings() {
        val input = calibrated.copy(groundTruthValidated=false)
        val s = VisionSafetyGate.assess(data(),input,1100,10f)
        assertEquals(VisionSafetyGate.DataStatus.UNCALIBRATED,s.status)
        assertFalse(s.lanesVisible)
    }

    @Test fun olderFrameCannotDisplayLead() {
        val s = VisionSafetyGate.assess(data(),calibrated,2000,10f)
        assertEquals(VisionSafetyGate.DataStatus.STALE,s.status)
        assertNull(s.leadDistanceMeters)
    }

    @Test fun validCalibratedResultCalculatesHeadwayAndTtc() {
        val s = VisionSafetyGate.assess(data(),calibrated,1150,10f)
        assertTrue(s.lanesVisible)
        assertTrue(s.leadVisible)
        assertEquals(20f,s.leadDistanceMeters!!,0.001f)
        assertEquals(2f,s.headwaySeconds!!,0.001f)
        assertEquals(4f,s.timeToCollisionSeconds!!,0.001f)
    }

    @Test fun lowConfidenceNeverCreatesFakeTargets() {
        val input = data().copy(laneConfidenceLeft=0.1f,
            laneConfidenceRight=0.2f,leadConfidence=0.3f)
        val s = VisionSafetyGate.assess(input,calibrated,1100,10f)
        assertEquals(VisionSafetyGate.DataStatus.LOW_CONFIDENCE,s.status)
        assertFalse(s.mayWarn)
    }

    @Test fun NaNInfAndClockRegressionAreRejected() {
        assertEquals(VisionSafetyGate.DataStatus.INVALID,
            VisionSafetyGate.assess(data().copy(leadConfidence=Float.NaN),
                calibrated,1100,10f).status)
        assertEquals(VisionSafetyGate.DataStatus.STALE,
            VisionSafetyGate.assess(data().copy(inferredElapsedMs=2000),
                calibrated,1100,10f).status)
        assertEquals(VisionSafetyGate.DataStatus.UNCALIBRATED,
            VisionSafetyGate.assess(data(), calibrated.copy(
                horizontalFovDegrees=Float.POSITIVE_INFINITY),1100,10f).status)
    }

    @Test fun outOfRangeDistanceAndClosingVelocityAreSuppressed() {
        val s = VisionSafetyGate.assess(data().copy(leadDistanceMeters=-3f,
            closingSpeedMetersPerSecond=-12f),
            calibrated,1100,10f)
        assertFalse(s.leadVisible)
        assertNull(s.timeToCollisionSeconds)
    }
}
