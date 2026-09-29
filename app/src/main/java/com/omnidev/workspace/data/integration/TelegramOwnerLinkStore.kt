package com.omnidev.workspace.data.integration

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

/** One local device owner for the Telegram listener. Outbound publishing uses separate settings. */
class TelegramOwnerLinkStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "omnidev_telegram_owner_v1",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    data class Owner(val chatId: Long, val userId: Long)

    fun owner(botToken: String): Owner? = synchronized(prefs) {
        if (prefs.getString("owner_token_hash", null) != digest(botToken)) return@synchronized null
        val chatId = prefs.getLong("owner_chat", 0)
        val userId = prefs.getLong("owner_user", 0)
        if (chatId <= 0 || userId <= 0) null else Owner(chatId, userId)
    }

    fun createPairingCode(botToken: String): String = synchronized(prefs) {
        require(botToken.isNotBlank()) { "Save the Telegram Bot Token first." }
        val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
        val code = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        prefs.edit().putString("pair_hash", digest(code))
            .putString("pair_token_hash", digest(botToken))
            .putLong("pair_expires", System.currentTimeMillis() + 10 * 60_000L)
            .putInt("pair_attempts", 0).commit().also {
                check(it) { "Unable to save Telegram pairing code." }
            }
        code
    }

    fun pair(botToken: String, chatId: Long, userId: Long, chatType: String, code: String): Boolean = synchronized(prefs) {
        if (!isPrivateOwnerChat(chatId, userId, chatType)) return@synchronized false
        val expected = prefs.getString("pair_hash", null) ?: return@synchronized false
        if (prefs.getLong("pair_expires", 0) < System.currentTimeMillis() ||
            prefs.getString("pair_token_hash", null) != digest(botToken) ||
            prefs.getInt("pair_attempts", 0) >= 5) return@synchronized false
        val matches = MessageDigest.isEqual(expected.toByteArray(), digest(code.trim()).toByteArray())
        if (!matches) {
            prefs.edit().putInt("pair_attempts", prefs.getInt("pair_attempts", 0) + 1).commit()
            return@synchronized false
        }
        prefs.edit().putLong("owner_chat", chatId).putLong("owner_user", userId)
            .putString("owner_token_hash", digest(botToken))
            .remove("pair_hash").remove("pair_token_hash").remove("pair_expires")
            .remove("pair_attempts").commit()
    }

    fun isAuthorized(botToken: String, chatId: Long, userId: Long, chatType: String): Boolean =
        isPrivateOwnerChat(chatId, userId, chatType) && owner(botToken) == Owner(chatId, userId)

    fun revoke() = synchronized(prefs) {
        check(prefs.edit().clear().commit()) { "Unable to revoke Telegram owner." }
    }

    companion object {
        internal fun isPrivateOwnerChat(chatId: Long, userId: Long, type: String): Boolean =
            type == "private" && chatId > 0 && chatId == userId

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
