package com.omnidev.workspace.data.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityStateManagerTest {
    @After fun disconnect() { AccessibilityStateManager.setServiceConnected(false) }

    @Test fun equivalentNodeWrappersReplaceTheOldSnapshot() {
        val first = AccessibilityNodeInfo.obtain()
        val second = AccessibilityNodeInfo.obtain(first)
        assertEquals(first, second)
        AccessibilityStateManager.updateRootNode(first)
        AccessibilityStateManager.updateRootNode(second)
        assertSame(second, AccessibilityStateManager.rootNode.value)
    }

    @Test fun revokedGenerationCannotPublishAnInFlightObservation() {
        AccessibilityStateManager.setServiceConnected(true)
        val generation = AccessibilityStateManager.snapshotGeneration()
        AccessibilityStateManager.updateRootNode(null)
        val root = AccessibilityNodeInfo.obtain()
        try {
            assertFalse(AccessibilityStateManager.publishRootNode(root, null, generation))
            assertNull(AccessibilityStateManager.rootNode.value)
        } finally { root.recycle() }
    }

    @Test fun clearingRootAlsoClearsLoadingAndNodeCount() {
        AccessibilityStateManager.setServiceConnected(true)
        assertTrue(AccessibilityStateManager.publishRootNode(AccessibilityNodeInfo.obtain(),
            AccessibilityScreenAnalyzer.Analysis(3, AccessibilityStateManager.ScreenClass.LOADING, false),
            AccessibilityStateManager.snapshotGeneration()))
        assertTrue(AccessibilityStateManager.isLoading.value)
        AccessibilityStateManager.updateRootNode(null)
        assertEquals(0, AccessibilityStateManager.lastNodeCount.value)
        assertFalse(AccessibilityStateManager.isLoading.value)
    }
}
