package com.omnidev.workspace.data.voice

import org.junit.Assert.*
import org.junit.Test

class WakePhrasePolicyTest {
    @Test fun customEnglishAndArabicPhrasesPreserveTheirWords() {
        assertEquals("يا أومني", WakePhrasePolicy.normalize("  يا   أومني  "))
        assertEquals("Hello Obieda", WakePhrasePolicy.normalize("Hello Obieda"))
        assertEquals("Hi Omni", WakePhrasePolicy.DEFAULT)
    }
    @Test fun emptyLongAndInvisiblePhrasesAreRejected() {
        for (phrase in listOf("", "   ", "a".repeat(61), "Hi\u0000Omni", "Hi\u202EOmni"))
            assertThrows(IllegalArgumentException::class.java) { WakePhrasePolicy.normalize(phrase) }
    }
}
