package com.omnidev.workspace.domain.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveObservationRedactorTest {

    @Test
    fun `redacts Arabic verification and temporary secret codes`() {
        val input = """
            رمز التحقق: 327623
            الرقم السري المؤقت 924123 متاح ل 3 دقائق
            رصيدك الحالي 2136.56 جنيه
        """.trimIndent()

        val result = SensitiveObservationRedactor.redact(input)

        assertFalse(result.contains("327623"))
        assertFalse(result.contains("924123"))
        assertTrue(result.contains("[REDACTED]"))
        assertTrue(result.contains("2136.56"))
    }

    @Test
    fun `redacts English credentials but preserves business data`() {
        val input = "otp=819204 token abcDEF1234567890 transaction=25000156742425 amount=22.50"

        val result = SensitiveObservationRedactor.redact(input)

        assertFalse(result.contains("819204"))
        assertFalse(result.contains("abcDEF1234567890"))
        assertTrue(result.contains("25000156742425"))
        assertTrue(result.contains("22.50"))
    }

    @Test
    fun `redacts bearer header`() {
        val result = SensitiveObservationRedactor.redact(
            "Authorization: Bearer eyJhbGciOi.secret.signature"
        )

        assertFalse(result.contains("eyJhbGciOi"))
        assertTrue(result.contains("Bearer [REDACTED]"))
    }
    @Test
    fun `ordinary token budget diagnostics are not treated as credentials`() {
        val input = "token budget exhausted after 12 iterations"

        val result = SensitiveObservationRedactor.redact(input)

        assertTrue(result.contains("token budget exhausted"))
        assertFalse(result.contains("[REDACTED]"))
    }


}
