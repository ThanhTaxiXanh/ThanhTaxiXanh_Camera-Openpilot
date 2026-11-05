package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class DirectCallbackEpochGateTest {
    @Test fun oldDecoderCannotFeedNewYuv() {
        val gate=DirectCallbackEpochGate()
        val old=gate.begin()
        gate.revoke()
        val fresh=gate.begin()
        val accepted=mutableListOf<String>()
        for ((lease,payload) in listOf(old to "old",fresh to "fresh")) {
            if (gate.isCurrent(lease)) accepted.add(payload)
        }
        assertEquals(listOf("fresh"),accepted)
    }
    @Test fun delayedUiExportRequiresRecheck() {
        val gate=DirectCallbackEpochGate()
        val lease=gate.begin()
        var exports=0
        val posted = { if(gate.isCurrent(lease)) exports++ }
        gate.revoke(); gate.begin(); posted()
        assertEquals(0,exports)
    }
    @Test fun oldDiscontinuityCannotResetNewOnnx() {
        val gate=DirectCallbackEpochGate()
        val old=gate.begin()
        gate.revoke()
        val current=gate.begin()
        var count=0
        for(lease in listOf(old,current,old)) if(gate.isCurrent(lease)) count++
        assertEquals(1,count)
    }
    @Test fun noLeaseBeforeBegin() {
        val gate=DirectCallbackEpochGate()
        assertFalse(gate.isCurrent(0L))
        assertFalse(gate.isCurrent(-1L))
        assertFalse(gate.isCurrent(1L))
    }
    @Test fun consecutiveBeginsRetireOldTokens() {
        val gate=DirectCallbackEpochGate()
        val one=gate.begin()
        val two=gate.begin()
        assertFalse(gate.isCurrent(one))
        assertTrue(gate.isCurrent(two))
    }
    @Test fun twentyThousandSessionChangesNeverReuseLease() {
        val gate=DirectCallbackEpochGate()
        var old=gate.begin()
        repeat(20_000) {
            gate.revoke()
            val fresh=gate.begin()
            assertFalse(gate.isCurrent(old))
            assertTrue(gate.isCurrent(fresh))
            old=fresh
        }
    }
    @Test fun revokeVisibleToAnotherWorkerThread() {
        val gate=DirectCallbackEpochGate()
        val old=gate.begin()
        val latch=java.util.concurrent.CountDownLatch(1)
        val result=java.util.concurrent.atomic.AtomicBoolean(true)
        val worker=Thread {
            latch.await()
            result.set(gate.isCurrent(old))
        }
        worker.start()
        gate.revoke()
        gate.begin()
        latch.countDown()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertFalse(result.get())
    }
}
