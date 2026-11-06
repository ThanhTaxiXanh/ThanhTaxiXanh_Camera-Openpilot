package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** No Android Surface, actual camera, model or optical latency is tested. */
class IjkSurfaceTapPolicyTest {
    private fun ticket(g: IjkSurfaceTapPolicy, time: Long) =
        g.request(time, true, 22f, 640, 480, true)

    @Test fun requiresPlayingNative640x480AndValidSurface() {
        val g = IjkSurfaceTapPolicy()
        g.start()
        assertNull(g.request(100L, false, 25f, 640, 480, true))
        assertNull(g.request(100L, true, Float.NaN, 640, 480, true))
        assertNull(g.request(100L, true, 25f, 1280, 720, true))
        assertNull(g.request(100L, true, 25f, 640, 480, false))
        assertNotNull(ticket(g, 100L))
    }

    @Test fun enforcesSingleOutstandingCopyAndAtMostThreePerSecond() {
        val g = IjkSurfaceTapPolicy()
        g.start()
        val a = ticket(g, 1_000L)!!
        assertNull(ticket(g, 1_400L)) // still in flight
        assertTrue(g.complete(a, 1_410L, true))
        assertNull(ticket(g, 1_200L))
        assertNotNull(ticket(g, 1_500L))
        assertEquals(1L, g.metrics().busySkips)
    }

    @Test fun slowPixelCopyIsNotDelivered() {
        val g = IjkSurfaceTapPolicy()
        g.start()
        val a = ticket(g, 1_000L)!!
        assertFalse(g.complete(a, 1_551L, true))
        assertEquals(1L, g.metrics().stale)
        assertNotNull(ticket(g, 1_600L))
    }

    @Test fun stopAndRestartRevokesOldCallbacksEvenIfClockLooksFresh() {
        val g = IjkSurfaceTapPolicy()
        g.start()
        val old = ticket(g, 1_000L)!!
        g.stop()
        assertFalse(g.complete(old, 1_020L, true))
        g.start()
        val next = ticket(g, 1_050L)!!
        assertNotEquals(old.epoch, next.epoch)
        assertFalse(g.complete(old, 1_060L, true))
        assertTrue(g.complete(next, 1_080L, true))
        assertEquals(1L, g.metrics().completed)
    }

    @Test fun failedPixelCopyCountsErrorAndFreesSingleSlot() {
        val g = IjkSurfaceTapPolicy()
        g.start()
        val a = ticket(g, 1_000L)!!
        assertFalse(g.complete(a, 1_010L, false))
        assertEquals(1L, g.metrics().errors)
        assertNotNull(ticket(g, 1_350L))
    }

    @Test fun duplicateAndNonMonotonicTimesCannotForgeProgress() {
        val g = IjkSurfaceTapPolicy()
        g.start()
        val a = ticket(g, 1_000L)!!
        assertFalse(g.complete(a, 999L, true))
        assertNull(ticket(g, 999L))
        assertNotNull(ticket(g, 1_350L))
    }

    @Test fun noRequestWhenGateNotArmed() {
        val g = IjkSurfaceTapPolicy()
        assertNull(ticket(g, 1_000L))
        g.start()
        g.stop()
        assertNull(ticket(g, 2_000L))
        assertFalse(g.metrics().active)
    }
}
