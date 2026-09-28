package com.omnidev.workspace.data.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextPolicyTest {
    @Test fun `one-time codes are hidden in English and Arabic`() {
        assertEquals("Your [one-time code hidden] expires soon", NotificationTextPolicy.redact("Your OTP: 123456 expires soon"))
        assertEquals("[one-time code hidden] صالح لدقيقة", NotificationTextPolicy.redact("رمز التحقق ٤٣٢١ صالح لدقيقة"))
    }

    @Test fun `ordinary message numbers remain readable`() {
        assertEquals("Order 123456 delivered", NotificationTextPolicy.redact("Order 123456 delivered"))
    }
}
