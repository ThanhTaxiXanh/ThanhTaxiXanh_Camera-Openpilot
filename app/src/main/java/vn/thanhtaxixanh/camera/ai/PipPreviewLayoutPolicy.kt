package vn.thanhtaxixanh.camera.ai

/** Layout decision for the Activity's direct outer children.
 * The camera preview remains visible in PiP; all siblings are hidden.
 * No Android dependencies so lifecycle decisions can be tested on the JVM.
 */
internal object PipPreviewLayoutPolicy {
    /** Restore the pre-PiP visibility; do not unhide controls that were GONE. */
    fun restoredVisibility(saved: List<Int>?, index: Int, count: Int, fallback: Int): Int {
        require(index in 0 until count)
        return if (saved?.size == count) saved[index] else fallback
    }

    fun showChild(inPip: Boolean, childIndex: Int, previewIndex: Int): Boolean {
        require(childIndex >= 0 && previewIndex >= 0)
        return !inPip || childIndex == previewIndex
    }
}
