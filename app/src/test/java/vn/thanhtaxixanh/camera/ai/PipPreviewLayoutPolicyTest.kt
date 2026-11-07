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

    @Test fun restoreKeepsPreviouslyCollapsedControlsHidden() {
        val header = Any()
        val preview = Any()
        val collapsed = Any()
        val invisible = Any()
        val before = mapOf(header to 0, preview to 0, collapsed to 8, invisible to 4)
        org.junit.Assert.assertEquals(8,
            PipPreviewLayoutPolicy.restoredVisibility(before, collapsed, 0))
        org.junit.Assert.assertEquals(4,
            PipPreviewLayoutPolicy.restoredVisibility(before, invisible, 0))
    }

    @Test fun reorderedChildrenRetainTheirOwnVisibility() {
        val first = Any()
        val second = Any()
        val saved = mapOf(first to 8, second to 4)
        val reordered = listOf(second, first)
        org.junit.Assert.assertEquals(4,
            PipPreviewLayoutPolicy.restoredVisibility(saved, reordered[0], 0))
        org.junit.Assert.assertEquals(8,
            PipPreviewLayoutPolicy.restoredVisibility(saved, reordered[1], 0))
    }

    @Test fun newChildFallsBackWithoutInheritingAnotherControlsState() {
        val saved = mapOf(Any() to 8)
        org.junit.Assert.assertEquals(0,
            PipPreviewLayoutPolicy.restoredVisibility(saved, Any(), 0))
    }
}
