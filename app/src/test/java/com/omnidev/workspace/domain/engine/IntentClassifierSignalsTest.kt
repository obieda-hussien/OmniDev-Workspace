package com.omnidev.workspace.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentClassifierSignalsTest {

    @Test
    fun `simple explanation stays chat`() {
        val input = "Explain what Room database is and why Android apps use it"
        val signals = IntentClassifier.analyze(input)

        assertTrue(signals.conversationalIntent > 0f)
        assertEquals(OmniMode.CHAT, IntentClassifier.classify(input))
    }

    @Test
    fun `single focused code mutation chooses agent`() {
        val input = "Fix the crash in ChatViewModel and verify the build"
        val signals = IntentClassifier.analyze(input)

        assertTrue(signals.executionIntent > 0.35f)
        assertTrue(signals.mutationIntent > 0f)
        assertEquals(OmniMode.AGENT, IntentClassifier.classify(input))
    }

    @Test
    fun `multi domain parallel implementation can choose team`() {
        val input = "Implement the UI, database migration, browser automation, security checks and tests in parallel across the project"
        val signals = IntentClassifier.analyze(input)
        val scores = IntentClassifier.scoreModes(signals)

        assertTrue(signals.domainCount >= 3)
        assertTrue(signals.parallelism >= 0.34f)
        assertTrue(scores.swarm >= scores.chat)
        assertEquals(OmniMode.SWARM, IntentClassifier.classify(input))
    }

    @Test
    fun `long research prompt does not become team only because it is long`() {
        val input = buildString {
            append("Research the latest Android architecture approaches and explain the tradeoffs. ")
            repeat(20) { append("Compare evidence and summarize findings without changing files. ") }
        }
        val signals = IntentClassifier.analyze(input)
        val scores = IntentClassifier.scoreModes(signals)

        assertTrue(signals.researchIntent > 0f)
        assertTrue(scores.swarm <= 0.52f || signals.executionIntent >= 0.35f)
        assertTrue(IntentClassifier.classify(input) != OmniMode.SWARM)
    }

    @Test
    fun `bucket key is stable coarse context not raw prompt`() {
        val a = IntentClassifier.analyze("Fix Kotlin code and run tests")
        val b = IntentClassifier.analyze("Fix Java code and run tests")

        assertTrue(a.bucketKey().length < 80)
        assertTrue(!a.bucketKey().contains("Kotlin", ignoreCase = true))
        assertTrue(!b.bucketKey().contains("Java", ignoreCase = true))
    }
    @Test
    fun `sms wallet request exposes messaging without irrelevant terminal domain`() {
        val domains = IntentClassifier.getRelevantDomains(
            "اقرأ رسائل اورنچ كاش واعرف آخر رصيد من الـ SMS"
        )

        assertTrue(IntentClassifier.ToolDomain.MESSAGING in domains)
        assertTrue(IntentClassifier.ToolDomain.CODE_TERMINAL !in domains)
    }

    @Test
    fun `single device setting keyword exposes device tools`() {
        val domains = IntentClassifier.getRelevantDomains(
            "غيّر سطوع الشاشة"
        )

        assertTrue(IntentClassifier.ToolDomain.DEVICE_CONTROL in domains)
    }

    @Test
    fun `focused code fix still exposes code terminal tools`() {
        val domains = IntentClassifier.getRelevantDomains(
            "Fix Kotlin build error in the project"
        )

        assertTrue(IntentClassifier.ToolDomain.CODE_TERMINAL in domains)
    }


}
