package com.omnidev.workspace.data.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * P11 device-only credential vault. No agent tool exposes secret reads or exports.
 *
 * Records, including labels and origins, are encrypted with an Android Keystore
 * AES-256-GCM key and stored in noBackupFilesDir. A random reference ID is the
 * only value agents may eventually receive. Reading a credential requires a
 * process-local, scoped, one-use grant issued by trusted confirmation UI.
 *
 * This is the storage and policy foundation, NOT an auto-login tool. UI approval
 * and browser integration must be implemented and tested before enabling use.
 */
class OmniVault(context: Context) {
    private val folder = File(context.applicationContext.noBackupFilesDir, "omni-vault-v1")

    data class Reference(val id: String, val label: String, val origin: String)

    private class Entry(val reference: Reference, val secret: CharArray) {
        fun clear() = secret.fill('\u0000')
    }

    /**
     * Store a user-supplied credential. The borrowed input is cleared even on
     * validation or encryption failure. Never pass a literal model-generated
     * password or an LLM tool argument into this API.
     */
    @Synchronized
    fun storeFromUserInput(label: String, siteUrl: String, secret: CharArray): Reference {
        var plaintext: ByteArray? = null
        val buffer = WipingBuffer()
        var encodedSecret: ByteArray? = null
        try {
            val origin = VaultAccessPolicy.canonicalOrigin(siteUrl)
                ?: throw IllegalArgumentException("Credential sites must have a valid HTTPS origin")
            val cleanLabel = label.trim()
            require(cleanLabel.length in 1..96)
            require(secret.size in 1..2048)
            check(folder.isDirectory || folder.mkdirs()) { "Vault directory unavailable" }

            val id = UUID.randomUUID().toString()
            val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(secret))
            val passwordBytes = ByteArray(encoded.remaining())
            encoded.get(passwordBytes)
            if (encoded.hasArray()) encoded.array().fill(0)
            encodedSecret = passwordBytes

            DataOutputStream(buffer).apply {
                writeByte(RECORD_VERSION)
                writeUTF(cleanLabel)
                writeUTF(origin)
                writeInt(passwordBytes.size)
                write(passwordBytes)
                flush()
            }
            plaintext = buffer.toByteArray()

            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(id.toByteArray(Charsets.US_ASCII))
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            check(iv.size == 12)

            val file = AtomicFile(File(folder, id))
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(RECORD_VERSION)
                stream.write(iv.size)
                stream.write(iv)
                stream.write(ciphertext)
                file.finishWrite(stream)
            } catch (error: Exception) {
                stream?.let(file::failWrite)
                throw error
            }
            return Reference(id, cleanLabel, origin)
        } finally {
            encodedSecret?.fill(0)
            plaintext?.fill(0)
            buffer.destroy()
            secret.fill('\u0000')
        }
    }

    /** Only non-secret metadata is returned to the caller. */
    @Synchronized
    fun listReferences(): List<Reference> {
        val files = folder.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && isReferenceId(it.name) }
            .map { file ->
                val entry = readEntry(file.name)
                try { entry.reference } finally { entry.clear() }
            }
            .sortedBy { it.label }
            .toList()
    }

    /**
     * Internal use-only boundary. The callback must never surface the credential
     * as ToolResult, AgentEvent, logs, crash data, screenshots or model context.
     * A malicious page's URL alone cannot mint a grant; the trusted UI must.
     */
    @Synchronized
    internal fun withApprovedSecret(
        referenceId: String,
        targetUrl: String,
        operation: VaultAccessPolicy.Operation,
        requestId: String,
        grant: VaultAccessPolicy.Grant,
        nowMillis: Long = System.currentTimeMillis(),
        use: (CharArray) -> Unit
    ) {
        require(isReferenceId(referenceId))
        val requestedOrigin = VaultAccessPolicy.canonicalOrigin(targetUrl)
            ?: throw SecurityException("A valid HTTPS origin is required")
        val scope = VaultAccessPolicy.Scope(referenceId, requestedOrigin, operation, requestId)
        if (!grant.matches(scope, nowMillis)) throw SecurityException("Credential approval missing or expired")

        val entry = readEntry(referenceId)
        try {
            if (entry.reference.origin != requestedOrigin) {
                throw SecurityException("Credential origin mismatch")
            }
            if (!grant.consume(scope, nowMillis)) throw SecurityException("Credential approval already used")
            use(entry.secret)
        } finally {
            entry.clear()
        }
    }

    /** Removal is local and irreversible; other credentials remain intact. */
    @Synchronized
    fun delete(referenceId: String): Boolean {
        require(isReferenceId(referenceId))
        val file = File(folder, referenceId)
        if (!file.exists()) return false
        AtomicFile(file).delete()
        return !file.exists()
    }

    private fun readEntry(referenceId: String): Entry {
        require(isReferenceId(referenceId))
        val encrypted = AtomicFile(File(folder, referenceId)).openRead().use { it.readBytes() }
        var plaintext: ByteArray? = null
        try {
            require(encrypted.size in 31..(MAX_RECORD_BYTES + 64))
            require(encrypted[0].toInt() == RECORD_VERSION)
            val ivSize = encrypted[1].toInt() and 0xff
            require(ivSize == 12 && encrypted.size >= 2 + ivSize + 16)

            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(
                Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(128, encrypted.copyOfRange(2, 2 + ivSize))
            )
            cipher.updateAAD(referenceId.toByteArray(Charsets.US_ASCII))
            plaintext = cipher.doFinal(encrypted.copyOfRange(2 + ivSize, encrypted.size))

            return DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
                require(input.readUnsignedByte() == RECORD_VERSION)
                val label = input.readUTF()
                val origin = input.readUTF()
                val secretLength = input.readInt()
                require(label.length in 1..96)
                require(origin == VaultAccessPolicy.canonicalOrigin(origin))
                require(secretLength in 1..MAX_SECRET_BYTES)
                require(input.available() == secretLength)
                val bytes = ByteArray(secretLength)
                try {
                    input.readFully(bytes)
                    val decoded = Charsets.UTF_8.decode(ByteBuffer.wrap(bytes))
                    val secret = CharArray(decoded.remaining())
                    decoded.get(secret)
                    if (decoded.hasArray()) decoded.array().fill('\u0000')
                    require(secret.isNotEmpty())
                    Entry(Reference(referenceId, label, origin), secret)
                } finally {
                    bytes.fill(0)
                }
            }
        } finally {
            encrypted.fill(0)
            plaintext?.fill(0)
        }
    }

    @Synchronized
    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private class WipingBuffer : ByteArrayOutputStream() {
        fun destroy() {
            buf.fill(0)
            reset()
        }
    }

    companion object {
        private const val KEY_ALIAS = "omnidev.omnivault.credential.v1"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val RECORD_VERSION = 1
        private const val MAX_SECRET_BYTES = 8192
        private const val MAX_RECORD_BYTES = 16384
        private val REFERENCE_ID = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        )

        private fun isReferenceId(id: String) = REFERENCE_ID.matches(id)
    }
}
