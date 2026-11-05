package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** Virtual sensor clock. This is NOT a physical Camera2 capture test. */
class Camera2SensorFrameGateTest {
    private val ms=1_000_000L

    @Test fun acceptsRealtimeSensorTimestampAndExposesCaptureNotCallback() {
        val gate=Camera2SensorFrameGate()
        val result=gate.inspect(10_000L*ms,10_200L*ms,true)
        assertTrue(result.accepted)
        assertEquals(10_000L,result.captureElapsedMs)
        assertEquals(200L,result.receiptAgeMs)
    }

    @Test fun oldImageCannotBeRefreshedByLateListenerCallback() {
        val gate=Camera2SensorFrameGate()
        assertEquals(Camera2SensorFrameGate.Verdict.TOO_OLD,
            gate.inspect(10_000L*ms,11_000L*ms,true).verdict)
    }

    @Test fun exactly550msIsValidBut551msIsStale() {
        val gate=Camera2SensorFrameGate()
        assertTrue(gate.inspect(10_000L*ms,10_550L*ms,true).accepted)
        assertEquals(Camera2SensorFrameGate.Verdict.TOO_OLD,
            gate.inspect(10_001L*ms,10_552L*ms,true).verdict)
    }

    @Test fun unknownSensorTimestampSourceCannotPretendToBeBootClock() {
        val gate=Camera2SensorFrameGate()
        assertEquals(Camera2SensorFrameGate.Verdict.UNKNOWN_TIMEBASE,
            gate.inspect(10_000L*ms,10_005L*ms,false).verdict)
        // Unknown clock must not poison the timestamp of a later verified camera.
        assertTrue(gate.inspect(10_100L*ms,10_110L*ms,true).accepted)
    }

    @Test fun duplicatesAndOutOfOrderImagesAreRejected() {
        val gate=Camera2SensorFrameGate()
        assertTrue(gate.inspect(10_000L*ms,10_010L*ms,true).accepted)
        assertEquals(Camera2SensorFrameGate.Verdict.NON_MONOTONIC,
            gate.inspect(10_000L*ms,10_020L*ms,true).verdict)
        assertEquals(Camera2SensorFrameGate.Verdict.NON_MONOTONIC,
            gate.inspect(9_990L*ms,10_030L*ms,true).verdict)
        assertTrue(gate.inspect(10_040L*ms,10_050L*ms,true).accepted)
    }

    @Test fun futureOrNegativeSensorTimeIsInvalid() {
        val gate=Camera2SensorFrameGate()
        for(t in listOf(0L,-1L,12_000L*ms)) {
            assertEquals(Camera2SensorFrameGate.Verdict.INVALID_CLOCK,
                gate.inspect(t,10_000L*ms,true).verdict)
        }
        assertEquals(Camera2SensorFrameGate.Verdict.INVALID_CLOCK,
            gate.inspect(10_000L*ms,0L,true).verdict)
    }

    @Test fun cameraSessionRestartAllowsNewIndependentTimeline() {
        val gate=Camera2SensorFrameGate()
        assertTrue(gate.inspect(100_000L*ms,100_010L*ms,true).accepted)
        gate.reset()
        assertTrue(gate.inspect(100_000L*ms,100_011L*ms,true).accepted)
    }

    @Test fun rejectedOldImageMustNotBeUsedAsTemporalPairStart() {
        val gate=Camera2SensorFrameGate()
        val paired=mutableListOf<Pair<Long,Long>>()
        var previous: Long?=null
        fun onCameraImage(sensorMs: Long, callbackMs: Long) {
            val decision=gate.inspect(sensorMs*ms,callbackMs*ms,true)
            if(!decision.accepted) { previous=null; return }
            val actual=decision.captureElapsedMs!!
            val old=previous
            if(old!=null && actual-old in 1L..500L)
                paired.add(old to actual)
            previous=actual
        }
        onCameraImage(1_000,1_020)
        onCameraImage(1_050,2_000) // stale callback: break pair
        onCameraImage(2_010,2_025)
        onCameraImage(2_050,2_065)
        assertEquals(listOf(2_010L to 2_050L),paired)
    }

    @Test fun hugeTimestampDoesNotOverflowToFresh() {
        val gate=Camera2SensorFrameGate()
        assertEquals(Camera2SensorFrameGate.Verdict.TOO_OLD,
            gate.inspect(1L,Long.MAX_VALUE,true).verdict)
        assertEquals(Camera2SensorFrameGate.Verdict.INVALID_CLOCK,
            gate.inspect(Long.MAX_VALUE,1000L,true).verdict)
    }

    @Test fun invalidThresholdConfigurationFailsClosed() {
        for(value in listOf(0L,-1L,30_001L)) {
            try {
                Camera2SensorFrameGate(value)
                fail("invalid freshness budget")
            } catch (_:IllegalArgumentException) {}
        }
    }
}
