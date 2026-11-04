package vn.thanhtaxixanh.camera.ai

/**
 * H.264 Annex-B access-unit boundary inspection.
 *
 * We must never resume a secondary decoder on an arbitrary P frame after
 * backpressure. Looking for 0x65 anywhere is incorrect: the NAL type may
 * only be read immediately after a 3- or 4-byte Annex-B start code.
 */
object H264AnnexB {
    fun hasIdr(accessUnit: ByteArray): Boolean {
        var i = 0
        while (i + 3 < accessUnit.size) {
            if (accessUnit[i] == 0.toByte() &&
                accessUnit[i + 1] == 0.toByte()) {
                val n = when {
                    accessUnit[i + 2] == 1.toByte() -> i + 3
                    i + 4 < accessUnit.size &&
                        accessUnit[i + 2] == 0.toByte() &&
                        accessUnit[i + 3] == 1.toByte() -> i + 4
                    else -> -1
                }
                if (n >= 0 && n < accessUnit.size) {
                    if ((accessUnit[n].toInt() and 0x1f) == 5) return true
                    i = n + 1
                    continue
                }
            }
            i++
        }
        return false
    }
}

/**
 * The secondary YUV decode path is allowed to drop frames only by
 * invalidating the entire GOP and waiting for an IDR. It never sends
 * broken prediction chains to MediaCodec as if they were valid images.
 */
class H264SecondarySyncGate {
    private var needIdr = true

    @Synchronized fun accept(accessUnit: ByteArray): Boolean {
        if (needIdr) {
            if (!H264AnnexB.hasIdr(accessUnit)) return false
            needIdr = false
        }
        return true
    }

    @Synchronized fun reset() { needIdr = true }

    @Synchronized fun awaitingIdr(): Boolean = needIdr
}
