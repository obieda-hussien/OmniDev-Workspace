package com.omnidev.workspace.data.admin

import android.view.accessibility.AccessibilityNodeInfo
import com.omnidev.workspace.data.accessibility.OmniAccessibilityService

/** Resolve only genuine SystemUI credential windows, including a keypad below a notification window. */
internal object SystemUiCredentialControls {
    private const val SYSTEM = "com.android.systemui"
    private val aliases = mapOf(
        "pinEntry" to listOf("pinEntry", "pin_entry"),
        "passwordEntry" to listOf("passwordEntry", "password_entry"),
        "lockPatternView" to listOf("lockPatternView", "lock_pattern_view"),
        "key_enter" to listOf("key_enter", "keyEnter", "enter_key", "keyguard_enter"))

    fun root(service: OmniAccessibilityService): AccessibilityNodeInfo? {
        fun credential(node: AccessibilityNodeInfo): Boolean = node.packageName?.toString() == SYSTEM &&
            listOf("pinEntry", "passwordEntry", "lockPatternView").any { id ->
                find(node, id)?.let { it.recycle(); true } == true
            }
        service.rootInActiveWindow?.let { if (credential(it)) return it else it.recycle() }
        for (window in service.windows.sortedByDescending { it.layer }) {
            val node = window.root ?: continue
            if (credential(node)) return node
            node.recycle()
        }
        return null
    }

    fun find(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val ids = aliases[id] ?: if (id.matches(Regex("key[0-9]"))) {
            val digit = id.last()
            listOf(id, "key_$digit", "digit$digit", "digit_$digit", "num_pad_key_$digit")
        } else listOf(id)
        val matches = ids.flatMap { root.findAccessibilityNodeInfosByViewId("$SYSTEM:id/$it") }
        val visible = matches.filter { it.isVisibleToUser && it.isEnabled }
        val match = visible.singleOrNull()
        matches.filter { it !== match }.forEach { it.recycle() }
        return match
    }

    fun emptyPin(input: AccessibilityNodeInfo) = input.text.isNullOrEmpty() &&
        (input.isPassword || input.className?.toString()?.endsWith("PasswordTextView") == true)
}
