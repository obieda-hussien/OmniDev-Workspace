package com.omnidev.workspace.data.admin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local AES-GCM PIN vault. No read/export tool, clipboard or shell command. */
class DevicePinVault(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "device-unlock-pin"))
    fun exists() = file.baseFile.exists()
    fun save(pin: CharArray) {
        require(pin.size in 4..16 && pin.all { it in '0'..'9' })
        val bytes = ByteArray(pin.size) { pin[it].code.toByte() }
        var stream: java.io.FileOutputStream? = null
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.doFinal(bytes)
            stream = file.startWrite()
            stream.write(cipher.iv.size)
            stream.write(cipher.iv)
            stream.write(ciphertext)
            file.finishWrite(stream)
        } catch (error: Exception) {
            stream?.let { file.failWrite(it) }
            throw error
        } finally { bytes.fill(0); pin.fill('\u0000') }
    }
    internal suspend fun <T> withPin(use: suspend (CharArray) -> T): T {
        val data = file.openRead().use { it.readBytes() }
        require(data.size in 30..128)
        val ivSize = data[0].toInt()
        require(ivSize == 12 && data.size > ivSize + 17)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(1, ivSize + 1)))
        val bytes = cipher.doFinal(data.copyOfRange(ivSize + 1, data.size))
        val pin = CharArray(bytes.size) { bytes[it].toInt().toChar() }
        try {
            require(pin.size in 4..16 && pin.all { it in '0'..'9' })
            return use(pin)
        } finally { bytes.fill(0); pin.fill('\u0000'); data.fill(0) }
    }
    fun delete() {
        file.delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    companion object { private const val ALIAS = "omnidev.device-unlock-pin.v1" }
}
