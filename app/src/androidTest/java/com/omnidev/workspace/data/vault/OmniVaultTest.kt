package com.omnidev.workspace.data.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OmniVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = OmniVault(context)
    private val created = mutableListOf<String>()

    @After fun tearDown() { created.forEach(vault::delete); vault.revokeAllApprovals() }

    private fun save(value: CharArray = charArrayOf('p','a','s','s','-','1','2','3')): VaultHandle =
        vault.put("https://example.com", "my account", value).also { created += it.id }

    @Test fun encryptedAtRestAndNeverExposesSecretAsMetadata() {
        val input = charArrayOf('p','a','s','s','-','1','2','3')
        val handle = save(input)
        assertTrue(input.all { it == '\u0000' })
        val file = File(context.noBackupFilesDir, "omni-agent-vault-v1/${handle.id}.enc")
        assertTrue(file.exists())
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("pass-123"))
        assertFalse(handle.toString().contains("pass-123"))
        assertTrue(vault.handles().any { it.id == handle.id })
    }

    @Test fun oneUseApprovalAndBorrowedCharsAreCleared() {
        val handle = save()
        val ticket = vault.approveFromTrustedUi(handle.id, handle.origin, VaultOperation.FILL_LOGIN)
        var borrowed: CharArray? = null
        vault.withApprovedSecret(ticket, handle.id, handle.origin, VaultOperation.FILL_LOGIN) {
            borrowed = it
            assertEquals("pass-123", String(it))
        }
        assertTrue(borrowed!!.all { it == '\u0000' })
        assertThrows(IllegalStateException::class.java) {
            vault.withApprovedSecret(ticket, handle.id, handle.origin, VaultOperation.FILL_LOGIN) { fail("replay") }
        }
    }

    @Test fun tamperedCiphertextCannotBeUsed() {
        val handle = save()
        val file = File(context.noBackupFilesDir, "omni-agent-vault-v1/${handle.id}.enc")
        val payload = file.readBytes()
        payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
        file.writeBytes(payload)
        assertThrows(Exception::class.java) {
            vault.withApprovedSecret("forged", handle.id, handle.origin, VaultOperation.FILL_LOGIN) { fail("tamper") }
        }
    }

    @Test fun deletingCredentialRevokesPendingApproval() {
        val handle = save()
        val ticket = vault.approveFromTrustedUi(handle.id, handle.origin, VaultOperation.READ_ACCOUNT)
        assertTrue(vault.delete(handle.id))
        assertThrows(Exception::class.java) {
            vault.withApprovedSecret(ticket, handle.id, handle.origin, VaultOperation.READ_ACCOUNT) { fail("deleted") }
        }
    }
}
