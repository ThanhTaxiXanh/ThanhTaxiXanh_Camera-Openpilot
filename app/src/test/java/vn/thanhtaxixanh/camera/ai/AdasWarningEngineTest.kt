package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class AdasWarningEngineTest {
    private val good = VisionSafetyGate.VisibleState(
        VisionSafetyGate.DataStatus.VISIBLE,
        lanesVisible=true,leadVisible=true,
        leadDistanceMeters=15f,headwaySeconds=1.5f,
        timeToCollisionSeconds=5f)

    @Test fun neverWarnFromMissingOrUncalibratedAI() {
        val w=AdasWarningEngine()
        val blocked=good.copy(status=VisionSafetyGate.DataStatus.UNCALIBRATED)
        assertNull(w.evaluate(1000,blocked,60f))
        assertNull(w.evaluate(1100,blocked,60f))
    }
    @Test fun minimumSpeedAndTwoSamplesRequired() {
        val w=AdasWarningEngine()
        assertNull(w.evaluate(1000,good,10f))
        assertNull(w.evaluate(1200,good,60f))
        val alert=w.evaluate(1400,good,60f)
        assertEquals(AdasWarningEngine.Kind.HEADWAY,alert?.kind)
    }
    @Test fun shortTtcTakesPriority() {
        val w=AdasWarningEngine()
        val risky=good.copy(timeToCollisionSeconds=1.8f)
        assertNull(w.evaluate(1000,risky,70f))
        assertEquals(AdasWarningEngine.Kind.COLLISION_RISK,
            w.evaluate(1200,risky,70f)?.kind)
    }
    @Test fun debounceResetsAfterLongGapOrHealthySample() {
        val w=AdasWarningEngine()
        assertNull(w.evaluate(1000,good,60f))
        assertNull(w.evaluate(2500,good,60f))
        assertNull(w.evaluate(2700,good.copy(headwaySeconds=3f),60f))
        assertNull(w.evaluate(2900,good,60f))
        assertNotNull(w.evaluate(3100,good,60f))
    }
    @Test fun cooldownAvoidsAlertEveryFrame() {
        val w=AdasWarningEngine()
        w.evaluate(1000,good,60f)
        assertNotNull(w.evaluate(1100,good,60f))
        assertNull(w.evaluate(1200,good,60f))
        assertNull(w.evaluate(1500,good,60f))
        assertNull(w.evaluate(6200,good,60f)) // first sample after a gap
        assertNotNull(w.evaluate(6300,good,60f))
    }
    @Test fun noWarningOnMissingGpsOrOldTimestamp() {
        val w=AdasWarningEngine()
        assertNull(w.evaluate(1000,good,null))
        assertNull(w.evaluate(2000,good,Float.NaN))
        assertNull(w.evaluate(3000,good,50f))
        assertNull(w.evaluate(3000,good,50f))
    }
}
