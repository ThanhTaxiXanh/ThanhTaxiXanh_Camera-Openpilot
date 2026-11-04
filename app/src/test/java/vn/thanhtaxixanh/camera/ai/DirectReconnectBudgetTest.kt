package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class DirectReconnectBudgetTest {
    @Test fun failuresWithinOneMinuteCannotRunAnUnlimitedReconnectStorm() {
        val budget=DirectReconnectBudget()
        val a=budget.onInterrupted(100_000L)
        val b=budget.onInterrupted(111_000L)
        val c=budget.onInterrupted(122_000L)
        val blocked=budget.onInterrupted(133_000L)
        assertEquals(DirectReconnectBudget.Status.RETRY,a.status)
        assertEquals(DirectReconnectBudget.Status.RETRY,b.status)
        assertEquals(DirectReconnectBudget.Status.RETRY,c.status)
        assertEquals(1_200L,a.delayMs)
        assertEquals(2_400L,b.delayMs)
        assertEquals(3_600L,c.delayMs)
        assertEquals(DirectReconnectBudget.Status.LIMIT_REACHED,blocked.status)
        assertFalse(blocked.permitted)
        assertEquals(3,budget.outstandingAttempts())
    }

    @Test fun longUptimeDoesNotClearRecentErrorsButOldFailuresExpire() {
        val budget=DirectReconnectBudget()
        assertTrue(budget.onInterrupted(5_000_000L).permitted)
        assertTrue(budget.onInterrupted(5_010_000L).permitted)
        assertTrue(budget.onInterrupted(5_020_000L).permitted)
        assertEquals(DirectReconnectBudget.Status.LIMIT_REACHED,
            budget.onInterrupted(5_021_000L).status)
        val afterWindow=budget.onInterrupted(5_060_001L)
        assertEquals(DirectReconnectBudget.Status.RETRY,afterWindow.status)
        assertEquals(3,afterWindow.attemptsWithinWindow)
        assertEquals(DirectReconnectBudget.Status.LIMIT_REACHED,
            budget.onInterrupted(5_060_002L).status)
    }

    @Test fun invalidMonotonicTimeDoesNotCorruptRecoveryBudget() {
        val budget=DirectReconnectBudget()
        assertEquals(DirectReconnectBudget.Status.INVALID_CLOCK,
            budget.onInterrupted(-1L).status)
        assertTrue(budget.onInterrupted(1_000L).permitted)
        assertEquals(DirectReconnectBudget.Status.INVALID_CLOCK,
            budget.onInterrupted(999L).status)
        assertTrue(budget.onInterrupted(1_001L).permitted)
        assertEquals(2,budget.outstandingAttempts())
    }

    @Test fun explicitSessionResetIsIndependentFromTimeBasedExpiry() {
        val budget=DirectReconnectBudget()
        repeat(3) { budget.onInterrupted(it.toLong()*1_000L) }
        assertFalse(budget.onInterrupted(3_100L).permitted)
        budget.reset()
        assertEquals(0,budget.outstandingAttempts())
        val fresh=budget.onInterrupted(3_101L)
        assertTrue(fresh.permitted)
        assertEquals(1,fresh.attemptsWithinWindow)
    }

    @Test fun thousandsOfLongSessionInterruptionsKeepConstantWindowSize() {
        val budget=DirectReconnectBudget()
        repeat(10_000) { i ->
            val result=budget.onInterrupted(i.toLong()*61_000L)
            assertTrue(result.permitted)
            assertEquals(1,result.attemptsWithinWindow)
        }
        assertEquals(1,budget.outstandingAttempts())
    }
}
