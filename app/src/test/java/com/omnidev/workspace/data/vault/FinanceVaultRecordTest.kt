package com.omnidev.workspace.data.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceVaultRecordTest {
    @Test fun acceptsIssuerAndExactSuffix() {
        assertTrue(FinanceVaultRecord.isValid(" Example Bank ", "0042"))
        assertTrue(FinanceVaultRecord.isValid("بنك مصر", "1234"))
    }

    @Test fun rejectsFullIdentifiersAndUnstructuredInput() {
        assertFalse(FinanceVaultRecord.isValid("Example Bank", "4111111111111111"))
        assertFalse(FinanceVaultRecord.isValid("Example Bank", "EG800001000001234567890123456"))
        assertFalse(FinanceVaultRecord.isValid("Example Bank", "123"))
        assertFalse(FinanceVaultRecord.isValid("Example Bank 1234", "0042"))
        assertFalse(FinanceVaultRecord.isValid("Example\nBank", "0042"))
    }
}
