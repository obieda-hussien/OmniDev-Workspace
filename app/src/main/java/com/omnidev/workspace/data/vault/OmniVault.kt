package com.omnidev.workspace.data.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * P11 credential store, intentionally not registered as an Agent/MCP/OmniLink tool.
 *
 * A separate UI approval boundary must mint a one-use grant before invoking
 * withApprovedSecret. The model receives only VaultHandle metadata, never
 * the plaintext, IV, keystore key or authorization ticket.
 *
 * Records are AES-GCM encrypted under an AndroidKeyStore non-exportable key,
 * stored in noBackupFilesDir, and bound to their UUID filenames as AAD.
 */
internal class OmniVault(
    context: Context,
    private val grants: VaultGrantLedger = VaultGrantLedger(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "omni-agent-vault-v1")
    private val lock = Any()

    fun put(origin: String, label: String, secret: CharArray): VaultHandle = synchronized(lock) {
        val normalized = VaultOrigin.normalize(origin) ?: throw IllegalArgumentException("Exact HTTPS origin required")
        require(label.isNotBlank() && label.length <= 120) { "Account label must be 1 to 120 characters" }
        require(secret.isNotEmpty() && secret.size <= 8_192) { "Secret length is out of range" }
        val handle = VaultHandle(UUID.randomUUID().toString(), normalized, label.trim(), clock())
        var plaintext = ByteArray(0)
        var ciphertext = ByteArray(0)
        try {
            plaintext = JSONObject()
                .put("origin", handle.origin)
                .put("label", handle.label)
                .put("createdAt", handle.createdAt)
                .put("secret", String(secret))
                .toString().toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(handle.id.toByteArray(Charsets.UTF_8))
            ciphertext = cipher.doFinal(plaintext)
            require(cipher.iv.size == IV_LENGTH)
            if (!directory.isDirectory && !directory.mkdirs()) error("Vault storage unavailable")
            val target = atomicFile(handle.id)
            var stream: java.io.FileOutputStream? = null
            try {
                stream = target.startWrite()
                stream.write(VERSION)
                stream.write(cipher.iv)
                stream.write(ciphertext)
                target.finishWrite(stream)
            } catch (failure: Exception) {
                stream?.let(target::failWrite)
                throw failure
            }
            handle
        } finally {
            secret.fill('\u0000')
            plaintext.fill(0)
            ciphertext.fill(0)
        }
    }

    fun handles(): List<VaultHandle> = synchronized(lock) {
        directory.listFiles().orEmpty().asSequence()
            .filter { it.isFile && FILE_NAME.matches(it.name) }
            .mapNotNull { file -> runCatching { decrypt(file.name.removeSuffix(".enc")).first }.getOrNull() }
            .sortedBy { it.createdAt }.toList()
    }

    /** Called only from a verified UI gesture; not reachable through tool dispatch. */
    fun approveFromTrustedUi(id: String, origin: String, operation: VaultOperation, ttlMs: Long = 60_000L): String {
        val handle = synchronized(lock) { decrypt(id).first }
        return grants.approve(handle, origin, operation, ttlMs)
    }

    /**
     * A grant is consumed before plaintext is returned to its trusted caller.
     * No secret is ever serialized into an LLM tool result, console or task state.
     */
    fun <T> withApprovedSecret(
        ticket: String, id: String, origin: String, operation: VaultOperation,
        use: (CharArray) -> T
    ): T {
        val (handle, chars) = synchronized(lock) { decrypt(id) }
        try {
            check(grants.consume(ticket, handle, origin, operation)) { "Vault approval absent, expired or revoked" }
            return use(chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    fun revoke(ticket: String) = grants.revoke(ticket)
    fun revokeAllApprovals() = grants.revokeAll()

    fun delete(id: String): Boolean = synchronized(lock) {
        require(HANDLE_ID.matches(id)) { "Invalid credential handle" }
        grants.revokeForHandle(id)
        val file = atomicFile(id)
        if (!file.baseFile.exists()) return@synchronized false
        file.delete()
        true
    }

    private fun decrypt(id: String): Pair<VaultHandle, CharArray> {
        require(HANDLE_ID.matches(id)) { "Invalid credential handle" }
        val stored = atomicFile(id).openRead().use { it.readBytes() }
        require(stored.size in (1 + IV_LENGTH + 16)..MAX_RECORD_BYTES) { "Vault record is invalid" }
        require(stored[0].toInt() == VERSION) { "Unsupported vault record" }
        var decrypted = ByteArray(0)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, stored.copyOfRange(1, 1 + IV_LENGTH)))
            cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
            decrypted = cipher.doFinal(stored.copyOfRange(1 + IV_LENGTH, stored.size))
            val body = JSONObject(String(decrypted, Charsets.UTF_8))
            val origin = VaultOrigin.normalize(body.getString("origin"))
                ?: throw IllegalArgumentException("Invalid vault origin")
            val handle = VaultHandle(id, origin, body.getString("label"), body.getLong("createdAt"))
            return handle to body.getString("secret").toCharArray()
        } finally {
            decrypted.fill(0)
            stored.fill(0)
        }
    }

    private fun atomicFile(id: String): AtomicFile {
        require(HANDLE_ID.matches(id)) { "Invalid credential handle" }
        return AtomicFile(File(directory, "$id.enc"))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    companion object {
        private const val VERSION = 1
        private const val IV_LENGTH = 12
        private const val MAX_RECORD_BYTES = 65_536
        private const val KEY_ALIAS = "omnidev.agent-vault.aes-gcm.v1"
        private val HANDLE_ID = Regex("[0-9a-f-]{36}")
        private val FILE_NAME = Regex("[0-9a-f-]{36}\\.enc")
    }
}
