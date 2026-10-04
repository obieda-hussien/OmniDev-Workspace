package com.omnidev.workspace.data.admin

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omnidev.workspace.data.accessibility.SemanticTreeParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtectedSemanticObservationTest {
    @Test fun passwordTextIsAbsentFromTreeSummaryAndFormPlaceholders() = assertPrivate(true, "password")
    @Test fun systemPinFieldIsPrivateEvenWhenOemOmitsPasswordFlag() = assertPrivate(false, "pinEntry")
    private fun assertPrivate(password: Boolean, id: String) {
        @Suppress("DEPRECATION")
        val node = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.systemui"
            className = "android.widget.EditText"
            viewIdResourceName = "com.android.systemui:id/$id"
            text = "private-test-fixture"
            contentDescription = "private-test-fixture"
            isPassword = password; isEditable = true; isFocusable = true; isFocused = true; isVisibleToUser = true
            setBoundsInScreen(Rect(0, 0, 300, 200))
        }
        try {
            val result = SemanticTreeParser.parse(node, "com.android.systemui", null)
            val observation = result.semanticTree + result.summary + result.detectedForms.toString()
            assertFalse(observation.contains("private-test-fixture"))
            assertTrue(result.semanticTree.contains("••••"))
        } finally { node.recycle() }
    }
}
