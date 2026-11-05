package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Deterministic JVM lease tests, not an Android Camera2 hardware test. */
class PhoneCameraSessionLeaseTest {
    @Test fun cameraCallbacksAfterPauseCannotReopenNativeSession() {
        val camera=PhoneCameraSessionLease()
        val opened=camera.begin()
        assertTrue(camera.owns(opened))
        camera.revoke()
        assertFalse(camera.owns(opened))
    }

    @Test fun lateOnOpenedFromPreviousSurfaceCannotOwnReplacement() {
        val camera=PhoneCameraSessionLease()
        val first=camera.begin()
        camera.revoke()
        val second=camera.begin()
        assertFalse(camera.owns(first))
        assertTrue(camera.owns(second))
    }

    @Test fun lateModelLoadAfterCancelCannotReactivateAi() {
        val model=PhoneCameraSessionLease()
        val pending=model.begin()
        model.revoke()
        assertFalse(model.owns(pending))
    }

    @Test fun newModelLoadDoesNotReviveOldOnnxResult() {
        val model=PhoneCameraSessionLease()
        val first=model.begin()
        model.revoke()
        val second=model.begin()
        val shown=mutableListOf<Int>()
        if(model.owns(first)) shown.add(1)
        if(model.owns(second)) shown.add(2)
        assertEquals(listOf(2), shown)
    }

    @Test fun independentCameraAndModelLeasesMustBothBeValid() {
        val camera=PhoneCameraSessionLease()
        val model=PhoneCameraSessionLease()
        val c=camera.begin()
        val m=model.begin()
        assertTrue(camera.owns(c)&&model.owns(m))
        camera.revoke()
        assertFalse(camera.owns(c)&&model.owns(m))
        assertTrue(model.owns(m))
    }

    @Test fun pendingCamera2ConfiguredCallbackIsBlockedByStop() {
        val camera=PhoneCameraSessionLease()
        val lease=camera.begin()
        val late=mutableListOf<()->Unit>()
        var nativeSessions=0
        late.add { if(camera.owns(lease)) nativeSessions++ }
        camera.revoke()
        late.forEach { it() }
        assertEquals(0,nativeSessions)
    }

    @Test fun twentyThousandSurfaceTransitionsNeverReuseLease() {
        val camera=PhoneCameraSessionLease()
        var old=camera.begin()
        repeat(20_000) {
            camera.revoke()
            val fresh=camera.begin()
            assertFalse(camera.owns(old))
            assertTrue(camera.owns(fresh))
            old=fresh
        }
    }

    @Test fun revokedLeaseIsVisibleToOtherThread() {
        val gate=PhoneCameraSessionLease()
        val lease=gate.begin()
        val release=CountDownLatch(1)
        val admitted=AtomicBoolean(true)
        val thread=Thread {
            release.await()
            admitted.set(gate.owns(lease))
        }
        thread.start()
        gate.revoke()
        release.countDown()
        thread.join(2000L)
        assertFalse(thread.isAlive)
        assertFalse(admitted.get())
    }

    @Test fun cameraAndModelIndividuallyRequireNewLeaseAfterStop() {
        val camera=PhoneCameraSessionLease()
        val model=PhoneCameraSessionLease()
        val c1=camera.begin()
        val m1=model.begin()
        model.revoke()
        assertFalse(model.owns(m1))
        assertTrue(camera.owns(c1))
        camera.revoke()
        assertFalse(camera.owns(c1))
        val c2=camera.begin()
        val m2=model.begin()
        assertTrue(camera.owns(c2))
        assertTrue(model.owns(m2))
        assertFalse(model.owns(m1))
    }
}
