package com.omnidev.workspace.data.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionRetryPolicyTest {

    @Test
    fun `read only Android queries are retry safe`() {
        assertTrue(
            ExecutionRetryPolicy.isSafeToRetry(
                "content query --uri content://sms --projection body,date"
            )
        )
        assertTrue(ExecutionRetryPolicy.isSafeToRetry("dumpsys battery"))
        assertTrue(ExecutionRetryPolicy.isSafeToRetry("getprop ro.product.model"))
    }

    @Test
    fun `idempotent state setters are retry safe`() {
        assertTrue(
            ExecutionRetryPolicy.isSafeToRetry(
                "settings put system screen_brightness 120"
            )
        )
        assertTrue(ExecutionRetryPolicy.isSafeToRetry("svc wifi disable"))
    }

    @Test
    fun `user visible mutations are at most once`() {
        assertFalse(ExecutionRetryPolicy.isSafeToRetry("input tap 400 800"))
        assertFalse(ExecutionRetryPolicy.isSafeToRetry("am start -a android.intent.action.VIEW"))
        assertFalse(ExecutionRetryPolicy.isSafeToRetry("pm install -r /data/local/tmp/app.apk"))
        assertFalse(ExecutionRetryPolicy.isSafeToRetry("pm uninstall com.example.app"))
    }

    @Test
    fun `mixed chains are not retried when any segment mutates`() {
        assertFalse(
            ExecutionRetryPolicy.isSafeToRetry(
                "settings get system screen_brightness && input tap 1 1"
            )
        )
    }
}
