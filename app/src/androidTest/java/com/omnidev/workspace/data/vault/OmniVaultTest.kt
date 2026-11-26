package com.omnidev.workspace.data.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OmniVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.noBackupFilesDir, "omni-vault-v1")
    private val vault = OmniVault(context)

    @Before fun clearBefore() { folder.listFiles()?.forEach { it.delete() } }
    @After fun clearAfter() { folder.listFiles()?.forEach { it.delete() } }

    @Test fun financialRecordsUseTheirOwnEncryptedVault() {
        val finance = OmniVault.finance(context)
        val previousIds = finance.listFinanceRecords().map { it.id }.toSet()
        finance.storeFinanceRecord("Example Bank", FinanceVaultRecord.Kind.PAYMENT_CARD, "0042")
        val saved = OmniVault.finance(context).listFinanceRecords().single { it.id !in previousIds }
        try {
            assertEquals("0042", saved.lastFour)
            assertEquals(FinanceVaultRecord.Kind.PAYMENT_CARD, saved.kind)
            assertFalse(vault.listReferences().any { it.id == saved.id })
            assertFalse(vault.delete(saved.id))
            val bytes = File(context.noBackupFilesDir, "omni-finance-vault-v1/${saved.id}").readBytes()
            assertFalse(bytes.toString(Charsets.UTF_8).contains("Example Bank"))
            assertThrows(IllegalStateException::class.java) { finance.listReferences() }
        } finally {
            finance.delete(saved.id)
        }
        assertFalse(finance.listFinanceRecords().any { it.id == saved.id })
    }

    private fun approved(ref: OmniVault.Reference): VaultAccessPolicy.Grant =
        VaultAccessPolicy.issueAfterUserConfirmation(
            VaultAccessPolicy.Scope(
                ref.id, ref.origin, VaultAccessPolicy.Operation.SIGN_IN, "request-12345"
            ),
            issuedAtMillis = 1000L
        )

    @Test fun encryptedRecordContainsNeitherPasswordNorAccountLabel() {
        val input = "correct-horse-test".toCharArray()
        val ref = vault.storeFromUserInput("My Secret Account", "https://login.example.com/path", input)
        assertTrue(input.all { it == '\u0000' })
        val onDisk = File(folder, ref.id).readBytes().toString(Charsets.UTF_8)
        assertFalse(onDisk.contains("correct-horse-test"))
        assertFalse(onDisk.contains("My Secret Account"))
        assertFalse(onDisk.contains("login.example.com"))
        assertEquals(listOf(ref), OmniVault(context).listReferences())

        var borrowed: CharArray? = null
        vault.withApprovedSecret(
            ref.id, "https://login.example.com/signin",
            VaultAccessPolicy.Operation.SIGN_IN, "request-12345", approved(ref), 1001L
        ) {
            borrowed = it
            assertEquals("correct-horse-test", String(it))
        }
        assertTrue(borrowed!!.all { it == '\u0000' })
    }

    @Test fun differentOriginAndDifferentOperationCannotUseCredential() {
        val ref = vault.storeFromUserInput("Site", "https://login.example.com", "secret".toCharArray())
        val grant = approved(ref)
        assertThrows(SecurityException::class.java) {
            vault.withApprovedSecret(
                ref.id, "https://login.example.com.evil.test",
                VaultAccessPolicy.Operation.SIGN_IN, "request-12345", grant, 1001L
            ) { fail("Must not receive secret") }
        }
        assertThrows(SecurityException::class.java) {
            vault.withApprovedSecret(
                ref.id, "https://login.example.com",
                VaultAccessPolicy.Operation.SEND_MESSAGE, "request-12345", grant, 1001L
            ) { fail("Must not receive secret") }
        }
        vault.withApprovedSecret(
            ref.id, "https://login.example.com",
            VaultAccessPolicy.Operation.SIGN_IN, "request-12345", grant, 1001L
        ) { assertEquals("secret", String(it)) }
        assertThrows(SecurityException::class.java) {
            vault.withApprovedSecret(
                ref.id, "https://login.example.com",
                VaultAccessPolicy.Operation.SIGN_IN, "request-12345", grant, 1002L
            ) { fail("One-use approval cannot replay") }
        }
    }

    @Test fun modifiedEncryptedRecordIsRejectedWithoutInvokingCaller() {
        val ref = vault.storeFromUserInput("Site", "https://site.example", "secret".toCharArray())
        val file = File(folder, ref.id)
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        var invoked = false
        assertThrows(Exception::class.java) {
            vault.withApprovedSecret(
                ref.id, "https://site.example",
                VaultAccessPolicy.Operation.SIGN_IN, "request-12345", approved(ref), 1001L
            ) { invoked = true }
        }
        assertFalse(invoked)
    }

    @Test fun corruptRecordDoesNotBlockListingOtherCredentials() {
        val healthy = vault.storeFromUserInput("Healthy", "https://healthy.example", "safe".toCharArray())
        val damaged = vault.storeFromUserInput("Damaged", "https://damaged.example", "secret".toCharArray())
        val file = File(folder, damaged.id)
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)

        assertEquals(listOf(healthy), vault.listReferences())
        assertThrows(Exception::class.java) {
            vault.withApprovedSecret(
                damaged.id, damaged.origin, VaultAccessPolicy.Operation.SIGN_IN,
                "request-12345", approved(damaged), 1001L
            ) { fail("Corrupted credential must never be exposed") }
        }
        vault.withApprovedSecret(
            healthy.id, healthy.origin, VaultAccessPolicy.Operation.SIGN_IN,
            "request-12345", approved(healthy), 1001L
        ) { assertEquals("safe", String(it)) }
    }

    @Test fun oversizedRecordFailsClosedWithoutBlockingHealthyReferences() {
        val healthy = vault.storeFromUserInput(
            "Healthy", "https://healthy.example", "safe".toCharArray()
        )
        val oversized = vault.storeFromUserInput(
            "Oversized", "https://oversized.example", "secret".toCharArray()
        )
        File(folder, oversized.id).outputStream().use { output ->
            val chunk = ByteArray(8192) { 0x42.toByte() }
            repeat(128) { output.write(chunk) }
        }

        assertEquals(listOf(healthy), vault.listReferences())
        assertThrows(IllegalArgumentException::class.java) {
            vault.withApprovedSecret(
                oversized.id, oversized.origin, VaultAccessPolicy.Operation.SIGN_IN,
                "request-12345", approved(oversized), 1001L
            ) { fail("Oversized credential must never be exposed") }
        }
        vault.withApprovedSecret(
            healthy.id, healthy.origin, VaultAccessPolicy.Operation.SIGN_IN,
            "request-12345", approved(healthy), 1001L
        ) { assertEquals("safe", String(it)) }
    }

    @Test fun callbackFailureStillWipesBorrowedSecret() {
        val ref = vault.storeFromUserInput("Site", "https://site.example", "secret".toCharArray())
        var borrowed: CharArray? = null
        assertThrows(IllegalStateException::class.java) {
            vault.withApprovedSecret(
                ref.id, "https://site.example",
                VaultAccessPolicy.Operation.SIGN_IN, "request-12345", approved(ref), 1001L
            ) { borrowed = it; error("Failed consumer") }
        }
        assertTrue(borrowed!!.all { it == '\u0000' })
    }

    @Test fun deletionAndInvalidReferenceAreFailClosed() {
        val ref = vault.storeFromUserInput("Site", "https://site.example", "secret".toCharArray())
        assertTrue(vault.delete(ref.id))
        assertFalse(vault.delete(ref.id))
        assertTrue(vault.listReferences().isEmpty())
        assertThrows(Exception::class.java) {
            vault.withApprovedSecret(
                ref.id, "https://site.example",
                VaultAccessPolicy.Operation.SIGN_IN, "request-12345", approved(ref), 1001L
            ) { fail("Deleted credential must not be recovered") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            vault.delete("../credentials")
        }
    }
}
