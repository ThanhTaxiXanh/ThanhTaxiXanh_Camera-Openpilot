package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipPreviewLayoutPolicyTest {
    @Test fun pipKeepsOnlyPreviewAmongOuterSiblings() {
        // Outer hierarchy: header, preview, IJK button, nested controls.
        for (index in 0..3) {
            if (index == 1) assertTrue(PipPreviewLayoutPolicy.showChild(true, index, 1))
            else assertFalse(PipPreviewLayoutPolicy.showChild(true, index, 1))
        }
    }

    @Test fun normalModeRestoresAllOuterChildren() {
        for (index in 0..3) {
            assertTrue(PipPreviewLayoutPolicy.showChild(false, index, 1))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPreviewIndexCannotHideVideo() {
        PipPreviewLayoutPolicy.showChild(true, 0, -1)
    }
}
