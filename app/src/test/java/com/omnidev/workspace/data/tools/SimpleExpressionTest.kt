package com.omnidev.workspace.data.tools

import org.junit.Assert.*
import org.junit.Test

class SimpleExpressionTest {
    @Test fun `local arithmetic and boolean grammar works without scripts`() {
        assertEquals(14.0, SafeExpressionParser("2 + 3 * 4").parse())
        assertEquals(true, SafeExpressionParser("10 > 5 && 3 < 7").parse())
    }
    @Test fun `system expressions are rejected`() {
        try { SafeExpressionParser("Runtime.exec('id')").parse(); fail("Expected rejection") }
        catch (_: ExpressionParseException) { }
    }
}
