package com.omnidev.system

import android.app.KeyguardManager
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import com.omnidev.system.contract.IOmniSystemService
import java.util.concurrent.Semaphore

/** Separate UID/process from all models, browsers and third-party connectors. */
class OmniSystemService : Service() {
    private val slots = Semaphore(2)
    private val binder = object : IOmniSystemService.Stub() {
        override fun execute(action: String, arguments: Bundle): Bundle {
            enforceCallingPermission(PERMISSION, "Omni system access requires the OEM signer")
            val uid = Binder.getCallingUid()
            val packages = packageManager.getPackagesForUid(uid).orEmpty()
            if (packages.size != 1 || packages.single() != CALLER ||
                packageManager.checkSignatures(uid, applicationInfo.uid) != PackageManager.SIGNATURE_MATCH)
                throw SecurityException("Caller is not the dedicated OEM assistant identity")
            if (!slots.tryAcquire()) return failure("BUSY", "System service is busy")
            try {
                val values = arguments.keySet().associateWith { arguments.get(it) }
                SystemActionPolicy.validate(action, values)?.let { return failure("INVALID_ARGUMENT", it) }
                val installedInSystem = applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val granted = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                if (!installedInSystem || !granted) return failure("ROM_NOT_PROVISIONED", "Install and grant the system service through the ROM build")
                if (SystemActionPolicy.mutation(action) && getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false)
                    return failure("USER_ACTION_REQUIRED", "Unlock the device before changing system state")
                // Permission and caller verification happen before clearing the remote identity.
                val identity = Binder.clearCallingIdentity()
                return try { perform(action, values) } finally { Binder.restoreCallingIdentity(identity) }
            } catch (error: Exception) {
                return failure("SYSTEM_ACTION_FAILED", error.javaClass.simpleName)
            } finally {
                Log.i("OmniSystemAudit", "uid=$uid action=$action") // Never log parameters, credentials or page contents.
                slots.release()
            }
        }
    }
    override fun onBind(intent: Intent) = binder

    private fun perform(action: String, args: Map<String, Any?>): Bundle {
        val audio = requireNotNull(getSystemService(AudioManager::class.java))
        val wifi = requireNotNull(applicationContext.getSystemService(WifiManager::class.java))
        return when (action) {
            "status" -> success().apply {
                putInt("protocol", 1); putBoolean("romProvisioned", true)
                putStringArrayList("actions", ArrayList(SystemActionPolicy.actions.sorted()))
            }
            "settings_read", "settings_write" -> {
                val key = args.getValue("key") as String
                if (action == "settings_write" && !Settings.System.putInt(contentResolver, key, args.getValue("value") as Int))
                    return failure("WRITE_REJECTED", "System rejected setting")
                val actual = Settings.System.getInt(contentResolver, key)
                if (action == "settings_write" && actual != args["value"]) return failure("VERIFY_FAILED", "Setting read-back did not match")
                success().apply { putInt("value", actual) }
            }
            "volume_read", "volume_set" -> {
                val stream = args.getValue("stream") as Int
                if (action == "volume_set") {
                    val value = args.getValue("value") as Int
                    require(value in 0..audio.getStreamMaxVolume(stream))
                    audio.setStreamVolume(stream, value, 0)
                    if (audio.getStreamVolume(stream) != value) return failure("VERIFY_FAILED", "Volume read-back did not match")
                }
                success().apply { putInt("value", audio.getStreamVolume(stream)); putInt("maximum", audio.getStreamMaxVolume(stream)) }
            }
            "wifi_read", "wifi_set" -> {
                if (action == "wifi_set") {
                    @Suppress("DEPRECATION")
                    val accepted = wifi.setWifiEnabled(args.getValue("enabled") as Boolean)
                    if (!accepted) return failure("WRITE_REJECTED", "Wi-Fi change rejected")
                }
                success().apply {
                    putBoolean("enabled", wifi.isWifiEnabled)
                    putBoolean("pending", action == "wifi_set" && wifi.isWifiEnabled != args["enabled"])
                }
            }
            else -> failure("UNAVAILABLE", "Action not implemented")
        }
    }
    private fun success() = Bundle().apply { putBoolean("ok", true) }
    private fun failure(code: String, message: String) = Bundle().apply { putBoolean("ok", false); putString("code", code); putString("message", message) }
    companion object {
        const val PERMISSION = "com.omnidev.system.permission.EXECUTE"
        const val CALLER = "com.omnidev.workspace.oem"
    }
}
