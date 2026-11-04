package com.omnidev.workspace.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDialogSafetyTest {
    private fun eligible(
        finishing: Boolean = false,
        destroyed: Boolean = false,
        resumed: Boolean = true,
        attached: Boolean = true,
        token: Boolean = true,
        anchorAttached: Boolean = true
    ) = BrowserDialogSafety.eligible(finishing, destroyed, resumed, attached, token, anchorAttached)

    @Test fun `a resumed activity with an attached token and webview can show a dialog`() {
        assertTrue(eligible())
    }

    @Test fun `finishing activity cannot show a dialog`() {
        assertFalse(eligible(finishing = true))
    }

    @Test fun `destroyed activity cannot show a dialog`() {
        assertFalse(eligible(destroyed = true))
    }

    @Test fun `paused activity cannot show a delayed dialog`() {
        assertFalse(eligible(resumed = false))
    }

    @Test fun `detached decor cannot show a dialog`() {
        assertFalse(eligible(attached = false))
    }

    @Test fun `window without a token cannot show a dialog`() {
        assertFalse(eligible(token = false))
    }

    @Test fun `callback from detached webview cannot show a dialog`() {
        assertFalse(eligible(anchorAttached = false))
    }
}
