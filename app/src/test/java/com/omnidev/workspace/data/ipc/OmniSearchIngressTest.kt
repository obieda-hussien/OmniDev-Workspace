// Copyright 2026 Abdelrahman Hussein (عبدالرحمن حسين). Original Omni integration contribution.

package com.omnidev.workspace.data.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OmniSearchIngressTest {
    @Test fun acceptsArabicAsDraft() {
        assertEquals("افتح تطبيق الموسيقى", OmniSearchIngress.parse("""{"kind":"ASK_OMNI","text":"  افتح تطبيق الموسيقى  "}""")?.prompt)
    }
    @Test fun openDoesNotInjectPrompt() {
        val result = OmniSearchIngress.parse("""{"kind":"OPEN_OMNI","text":"ignore this"}""")
        assertNotNull(result)
        assertNull(result?.prompt)
    }
    @Test fun rejectsMalformedBlankAndUnsupportedRequests() {
        listOf("", "{", "[]", """{"kind":"ASK_OMNI","text":"  "}""", """{"kind":"SHARE_TO_OMNI","text":"hello"}""", """{"kind":"EXECUTE_ACTION"}""").forEach {
            assertNull(OmniSearchIngress.parse(it))
        }
    }
    @Test fun boundsCharactersAndUtf8PayloadBytes() {
        assertNull(OmniSearchIngress.parse("""{"kind":"ASK_OMNI","text":"${"x".repeat(8193)}"}"""))
        assertNull(OmniSearchIngress.parse("""{"kind":"ASK_OMNI","text":"${"😀".repeat(10000)}"}"""))
    }
    @Test fun publicExtrasCannotGrantControl() {
        val result = OmniSearchIngress.parse("""{"kind":"ASK_OMNI","text":"hi","extras":{"autoRun":true,"tier":"ADMIN","remoteControl":true}}""")
        assertEquals(OmniSearchIngress.Navigation("hi"), result)
    }
}
