package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** JVM only. Native Android Canvas and real GNSS remain unverified. */
class HudDisplayFormatTest {
    private val metric = HudDisplayFormat.UnitMode.METRIC
    private val imperial = HudDisplayFormat.UnitMode.IMPERIAL

    @Test fun metricIsDefaultIncludingUnrecognizedSetting() {
        assertEquals(metric,HudDisplayFormat.UnitMode.fromPreference(null))
        assertEquals(metric,HudDisplayFormat.UnitMode.fromPreference(""))
        assertEquals(metric,HudDisplayFormat.UnitMode.fromPreference("mph"))
        assertEquals(metric,HudDisplayFormat.UnitMode.fromPreference("km/h · m"))
    }

    @Test fun savedImperialChoiceIsRecognizedExactly() {
        assertEquals(imperial,HudDisplayFormat.UnitMode.fromPreference("mph · ft"))
    }

    @Test fun metricSpeedUsesGpsKmhWithoutCalibrationOffset() {
        assertEquals("36 km/h",HudDisplayFormat.labels(36f,null,metric,true).speed)
    }

    @Test fun imperialSpeedConvertsKmhToMphExactlyOnce() {
        assertEquals("22 mph",HudDisplayFormat.labels(36f,null,imperial,true).speed)
        assertEquals("60 mph",HudDisplayFormat.labels(96.56064f,null,imperial,true).speed)
    }

    @Test fun hidingBrandNeverHidesIndependentSpeed() {
        val a=HudDisplayFormat.labels(72f,null,metric,true)
        val b=HudDisplayFormat.labels(72f,null,metric,false)
        assertEquals("72 km/h",a.speed)
        assertEquals(a.speed,b.speed)
        assertNull(b.brand)
        assertNotNull(a.brand)
    }

    @Test fun hidingBrandDoesNotConcealVerifiedMetricLead() {
        val a=HudDisplayFormat.labels(null,30f,metric,false)
        assertEquals("30 m · CHỈ THAM KHẢO",a.lead)
        assertNull(a.brand)
    }

    @Test fun imperialLeadDistanceChangesMetersToFeet() {
        val a=HudDisplayFormat.labels(null,10f,imperial,false)
        assertEquals("33 ft · CHỈ THAM KHẢO",a.lead)
    }

    @Test fun roundingOnlyAppliesAtTwentyMetersAndAbove() {
        val a=HudDisplayFormat.labels(null,23f,metric,false,true)
        val b=HudDisplayFormat.labels(null,19f,metric,false,true)
        assertEquals("25 m · CHỈ THAM KHẢO",a.lead)
        assertEquals("19 m · CHỈ THAM KHẢO",b.lead)
    }

    @Test fun imperialRoundingOccursInMetersBeforeUnitChange() {
        val a=HudDisplayFormat.labels(null,23f,imperial,false,true)
        assertEquals("82 ft · CHỈ THAM KHẢO",a.lead)
    }

    @Test fun absentMeasurementsRemainAbsentInBothUnitModes() {
        for(u in HudDisplayFormat.UnitMode.entries) {
            val x=HudDisplayFormat.labels(null,null,u,true)
            assertNull(x.speed)
            assertNull(x.lead)
        }
    }

    @Test fun invalidSpeedNeverBecomesZeroOrExcessiveReading() {
        val bad=listOf(Float.NaN,Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,-1f,241f)
        for(v in bad) assertNull(HudDisplayFormat.labels(v,null,imperial,true).speed)
    }

    @Test fun invalidLeadNeverBecomesDistanceLabel() {
        val bad=listOf(Float.NaN,Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,0f,-1f,201f)
        for(v in bad) assertNull(HudDisplayFormat.labels(null,v,imperial,true).lead)
    }

    @Test fun zeroGpsSpeedIsValidButNotAnInferredVehicleDetection() {
        val x=HudDisplayFormat.labels(0f,null,imperial,false)
        assertEquals("0 mph",x.speed)
        assertNull(x.lead)
    }

    @Test fun displayHasNoCountrySpecificNumberFormatting() {
        val x=HudDisplayFormat.labels(111f,100f,metric,true)
        assertEquals("111 km/h",x.speed)
        assertEquals("100 m · CHỈ THAM KHẢO",x.lead)
    }
}
