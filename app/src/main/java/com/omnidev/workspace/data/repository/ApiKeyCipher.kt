package com.omnidev.workspace.data.repository

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local authenticated encryption; bind each token to its provider's storage key. */
internal object ApiKeyCipher {
    const val PREFIX = "omni-key-v1:"
    fun encrypt(name: String, value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            return PREFIX + Base64.encodeToString(cipher.iv + cipher.doFinal(bytes), Base64.NO_WRAP)
        } finally { bytes.fill(0) }
    }
    fun decrypt(name: String, value: String): String {
        check(value.startsWith(PREFIX)) { "API key storage migration is incomplete" }
        val bytes = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        return try { String(plain, Charsets.UTF_8) } finally { plain.fill(0); bytes.fill(0) }
    }
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private const val ALIAS = "omnidev.provider-api-keys.v1"
}
