package com.omnidev.workspace.data.admin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DevicePinVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = DevicePinVault(context)
    @Before fun cleanBefore() { vault.delete() }
    @After fun cleanAfter() { vault.delete() }
    @Test fun encryptsAtRestAndZerosBorrowedArrays() {
        val fixture = charArrayOf('8', '2', '6', '4')
        vault.save(fixture)
        assertTrue(fixture.all { it == '\u0000' })
        assertTrue(vault.exists())
        val ciphertext = File(context.noBackupFilesDir, "device-unlock-pin").readBytes()
        assertFalse(ciphertext.toString(Charsets.UTF_8).contains("8264"))
        var borrowed: CharArray? = null
        vault.withPin { pin ->
            borrowed = pin
            assertTrue(pin.contentEquals(charArrayOf('8', '2', '6', '4')))
        }
        assertTrue(borrowed!!.all { it == '\u0000' })
    }
    @Test fun modifiedCiphertextIsRejectedBeforeUse() {
        vault.save(charArrayOf('8', '2', '6', '4'))
        val file = File(context.noBackupFilesDir, "device-unlock-pin")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        var used = false
        assertThrows(Exception::class.java) { vault.withPin { used = true } }
        assertFalse(used)
    }
    @Test fun callbackFailureStillZerosTheDecryptedPin() {
        vault.save(charArrayOf('8', '2', '6', '4'))
        var borrowed: CharArray? = null
        assertThrows(IllegalStateException::class.java) {
            vault.withPin { borrowed = it; error("Test callback failure") }
        }
        assertTrue(borrowed!!.all { it == '\u0000' })
    }
    @Test fun deletionRemovesTheLocalCredential() {
        vault.save(charArrayOf('8', '2', '6', '4'))
        vault.delete()
        assertFalse(vault.exists())
        assertThrows(Exception::class.java) { vault.withPin { fail("Deleted PIN must not be readable") } }
    }
}
