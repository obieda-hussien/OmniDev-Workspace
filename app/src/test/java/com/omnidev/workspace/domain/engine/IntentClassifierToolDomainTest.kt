package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.domain.engine.IntentClassifier.ToolDomain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentClassifierToolDomainTest {

    @Test
    fun `rish system task selects core code and device without unrelated messaging`() {
        val domains = IntentClassifier.getRelevantDomains(
            "Fix rish and Shizuku settings put failures in the Android agent runtime"
        )

        assertTrue(ToolDomain.CORE in domains)
        assertTrue(ToolDomain.CODE_TERMINAL in domains)
        assertTrue(ToolDomain.DEVICE_CONTROL in domains)
        assertFalse(ToolDomain.MESSAGING in domains)
        assertFalse(ToolDomain.ANALYTICS in domains)
    }

    @Test
    fun `normal code task does not inject device or web capabilities`() {
        val domains = IntentClassifier.getRelevantDomains(
            "Fix this Kotlin parser bug and run the unit tests"
        )

        assertTrue(ToolDomain.CORE in domains)
        assertTrue(ToolDomain.CODE_TERMINAL in domains)
        assertFalse(ToolDomain.DEVICE_CONTROL in domains)
        assertFalse(ToolDomain.WEB_SEARCH in domains)
        assertFalse(ToolDomain.MESSAGING in domains)
    }

    @Test
    fun `web research explicitly enables web tools`() {
        val domains = IntentClassifier.getRelevantDomains(
            "Research the latest GitHub documentation online and compare the API changes"
        )

        assertTrue(ToolDomain.CORE in domains)
        assertTrue(ToolDomain.WEB_SEARCH in domains)
    }

    @Test
    fun `critical execution tools are not classified as generic general`() {
        assertTrue(IntentClassifier.getToolDomain("agent_runtime") == ToolDomain.CODE_TERMINAL)
        assertTrue(IntentClassifier.getToolDomain("privileged_tool") == ToolDomain.DEVICE_CONTROL)
        assertTrue(IntentClassifier.getToolDomain("root_shell_tool") == ToolDomain.ROOT_CONTROL)
        assertTrue(IntentClassifier.getToolDomain("search_knowledge") == ToolDomain.CORE)
    }
    @Test
    fun `ordinary Android system task does not expose root-only tools`() {
        val domains = IntentClassifier.getRelevantDomains(
            "Read screen brightness and dumpsys battery using Shizuku"
        )

        assertTrue(ToolDomain.DEVICE_CONTROL in domains)
        assertFalse(ToolDomain.ROOT_CONTROL in domains)
    }

    @Test
    fun `explicit Magisk root task exposes root-only tools`() {
        val domains = IntentClassifier.getRelevantDomains(
            "Use Magisk root access to run a root-only command"
        )

        assertTrue(ToolDomain.ROOT_CONTROL in domains)
        assertTrue(ToolDomain.DEVICE_CONTROL in domains)
    }


}
