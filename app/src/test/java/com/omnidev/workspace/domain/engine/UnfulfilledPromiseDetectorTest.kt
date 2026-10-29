package com.omnidev.workspace.domain.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnfulfilledPromiseDetectorTest {
    @Test fun `announced action in english is detected`() {
        assertTrue(UnfulfilledPromiseDetector.isUnfulfilled("I'll start by checking the folder:"))
        assertTrue(UnfulfilledPromiseDetector.isUnfulfilled("Let me look at the logs now"))
    }

    @Test fun `announced action in arabic is detected`() {
        assertTrue(UnfulfilledPromiseDetector.isUnfulfilled("فهمت المطلوب.\n\nهبدأ فوراً أفحص مجلد Omni وأعمل سكربت للاكتشاف"))
        assertTrue(UnfulfilledPromiseDetector.isUnfulfilled("سأقوم بتشغيل الاختبارات الآن"))
    }

    @Test fun `a reply that ends open is detected even without a verb`() {
        assertTrue(UnfulfilledPromiseDetector.isUnfulfilled("The next steps are the following..."))
    }

    @Test fun `completed work with evidence is not a promise`() {
        assertFalse(UnfulfilledPromiseDetector.isUnfulfilled("Done. All 12 tests passed. Let me know if you need more."))
        assertFalse(UnfulfilledPromiseDetector.isUnfulfilled("✅ تم تشغيل الاختبارات: النجاح 12 من 12."))
    }

    @Test fun `plain answers and empty text are not promises`() {
        assertFalse(UnfulfilledPromiseDetector.isUnfulfilled("The package name is ai.perplexity.app.android."))
        assertFalse(UnfulfilledPromiseDetector.isUnfulfilled("   "))
    }

    @Test fun `long substantive answers are never treated as promises`() {
        assertFalse(UnfulfilledPromiseDetector.isUnfulfilled("I'll explain. " + "detail ".repeat(400)))
    }
}
