package com.omnidev.workspace.data.voice

import android.content.Context
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credential-encrypted, backup-excluded device storage. No export/read tool is exposed. */
class WakeProfileStore(context: Context) {
    private val context = context.applicationContext
    private val file get() = AtomicFile(File(context.noBackupFilesDir, "personal-wake-model.v1"))
    private fun unlocked() = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
    fun exists() = unlocked() && file.baseFile.exists()
    fun save(model: PersonalWakeModel) = synchronized(LOCK) {
        check(unlocked())
        val plain = model.encode()
        var stream: java.io.FileOutputStream? = null
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val encrypted = cipher.doFinal(plain)
            stream = file.startWrite()
            stream.write(cipher.iv); stream.write(encrypted)
            file.finishWrite(stream)
        } catch (e: Exception) { stream?.let { file.failWrite(it) }; throw e }
        finally { plain.fill(0) }
    }
    fun load(): PersonalWakeModel = synchronized(LOCK) {
        check(unlocked())
        require(file.baseFile.length() in 128..220_028)
        val encrypted = file.openRead().use { it.readBytes() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        }
        val plain = cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
        try { PersonalWakeModel.decode(plain) } finally { plain.fill(0); encrypted.fill(0) }
    }
    fun delete() = synchronized(LOCK) {
        if (unlocked()) file.delete()
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
    companion object { private val LOCK = Any(); private const val ALIAS = "omnidev.personal-wake.v1" }
}
