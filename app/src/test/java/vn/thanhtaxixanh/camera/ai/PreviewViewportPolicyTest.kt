package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** Pure viewport geometry regression tests; not optical camera proof. */
class PreviewViewportPolicyTest {
    @Test fun observedPortraitPhoneGetsActualVideoHeightNotWeightedZero() {
        // Uploaded screen recording was 512x1136; physical viewport width
        // is reduced by app padding before computing the 4:3 area.
        assertEquals(366, PreviewViewportPolicy.heightPx(488,1136,1f))
    }

    @Test fun commonAndroidPhoneDisplaysFourByThreeWithoutStretch() {
        assertEquals(756, PreviewViewportPolicy.heightPx(1008,2400,3f))
    }

    @Test fun tinyHeightKeepsVideoWithinAvailableViewport() {
        assertEquals(90, PreviewViewportPolicy.heightPx(400,200,3f))
    }

    @Test fun narrowWindowKeepsMinimumVisibleWhenBudgetPermits() {
        assertEquals(160, PreviewViewportPolicy.heightPx(100,800,1f))
    }

    @Test fun previewRespectsScreenHeightCapInLandscape() {
        assertEquals(486, PreviewViewportPolicy.heightPx(1800,1080,2f))
    }

    @Test fun hugeWidthUsesLongArithmeticAndSafeIntSaturation() {
        assertEquals(900,PreviewViewportPolicy.heightPx(Int.MAX_VALUE,2000,2f))
    }

    @Test fun zeroAndInvalidInputsFailBeforeLayoutCreation() {
        for (width in listOf(0,-1)) {
            try { PreviewViewportPolicy.heightPx(width,1000,2f); fail("bad width") }
            catch (_: IllegalArgumentException) {}
        }
        for (height in listOf(0,-20)) {
            try { PreviewViewportPolicy.heightPx(200,height,2f); fail("bad height") }
            catch (_: IllegalArgumentException) {}
        }
        for (density in listOf(0f,-1f,Float.NaN,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            try { PreviewViewportPolicy.heightPx(200,1000,density);fail("bad density") }
            catch (_: IllegalArgumentException) {}
        }
    }
}
