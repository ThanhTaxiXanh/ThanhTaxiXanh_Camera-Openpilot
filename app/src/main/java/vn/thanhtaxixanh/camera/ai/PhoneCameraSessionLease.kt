package vn.thanhtaxixanh.camera.ai

/**
 * Atomic, synchronized admission lease for asynchronous Camera2 callbacks and
 * model loaders. Activity pause, Surface destruction or model cancellation
 * revokes the lease BEFORE native objects are released. A caller must check
 * ownership again in every delayed Android UI continuation.
 */
class PhoneCameraSessionLease {
    private var sequence=0L
    private var active=false

    @Synchronized fun begin(): Long {
        check(sequence != Long.MAX_VALUE) { "Session lease exhausted" }
        sequence++
        active=true
        return sequence
    }

    @Synchronized fun revoke() {
        active=false
        if (sequence < Long.MAX_VALUE) sequence++
    }

    @Synchronized fun owns(token: Long): Boolean =
        active && token > 0L && sequence == token
}
