package com.omnidev.workspace.data.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Device Administrator receiver for OmniDev.
 *
 * When the user grants Device Admin status in Settings → Security → Device
 * Administrators, this receiver enables:
 * - **Lock screen** — agent can lock the device on command
 * - **Password policy** — agent can enforce minimum PIN/password complexity
 * - **Wipe data** — factory-reset (extreme; gated behind ConfirmationGate)
 * - **Disable Camera** — globally disables hardware cameras for security
 * - **Keyguard Features** — controls what is visible on the lock screen
 *
 * All destructive actions are safeguarded by the ReAct confirmation gate;
 * this class only provides the plumbing.
 */
class OmniDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "OmniDeviceAdmin"

        /** Returns the [ComponentName] used to activate this admin receiver. */
        fun getComponentName(context: Context): ComponentName =
            ComponentName(context, OmniDeviceAdminReceiver::class.java)

        /** Checks whether Device Admin is currently active for this app. */
        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            return dpm.isAdminActive(getComponentName(context))
        }

        /** * Checks whether this app is the Device Owner (the ultimate God-Mode).
         * Required for setting password policies on modern Android versions (11+).
         */
        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            return dpm.isDeviceOwnerApp(context.packageName)
        }

        /**
         * Launches the system prompt requesting Device Admin activation.
         *
         * @param context Activity or Application context (adds FLAG_ACTIVITY_NEW_TASK).
         * @param explanation Optional description shown in the system dialog.
         */
        fun requestAdminActivation(context: Context, explanation: String? = null) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getComponentName(context))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    explanation ?: "OmniDev needs Device Admin to lock the screen, disable cameras, and enforce security policies on your command."
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch Device Admin activation", e)
            }
        }

        /**
         * Locks the screen immediately.
         *
         * @return `true` if the lock command was sent, `false` if admin is not active.
         */
        fun lockScreen(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            return if (dpm.isAdminActive(getComponentName(context))) {
                dpm.lockNow()
                Log.i(TAG, "Screen locked via Device Admin")
                true
            } else {
                Log.w(TAG, "Cannot lock screen — Device Admin not active")
                false
            }
        }

        /**
         * Disables or enables all device cameras globally.
         * Perfect for a "Secure Mode" agent routine.
         * * @return `true` if the policy was applied.
         */
        fun setCameraDisabled(context: Context, disabled: Boolean): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            return if (dpm.isAdminActive(getComponentName(context))) {
                dpm.setCameraDisabled(getComponentName(context), disabled)
                Log.i(TAG, "Camera disabled state set to: $disabled")
                true
            } else {
                Log.w(TAG, "Cannot change camera state — Device Admin not active")
                false
            }
        }

        /**
         * Factory resets the device!
         * EXTREMELY DANGEROUS: Wipes all user data.
         * * @return `true` if the wipe command was accepted.
         */
        fun wipeDeviceData(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            return if (dpm.isAdminActive(getComponentName(context))) {
                Log.e(TAG, "INITIATING DEVICE FACTORY RESET!")
                dpm.wipeData(0) // 0 performs a standard data wipe
                true
            } else {
                Log.w(TAG, "Cannot wipe device — Device Admin not active")
                false
            }
        }

        /**
         * Sets the minimum password length (PIN/password).
         * NOTE: On modern Android, this generally requires Device Owner privileges.
         *
         * @return `true` if the policy was applied, `false` if admin is not active.
         */
        @Suppress("DEPRECATION")
        fun setMinPasswordLength(context: Context, minLength: Int): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            return if (dpm.isAdminActive(getComponentName(context))) {
                if (isDeviceOwner(context) || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                    dpm.setPasswordMinimumLength(getComponentName(context), minLength)
                    Log.i(TAG, "Minimum password length set to $minLength")
                    true
                } else {
                    Log.w(TAG, "Cannot set password length — Device Owner required on Android 11+")
                    false
                }
            } else {
                Log.w(TAG, "Cannot set password policy — Device Admin not active")
                false
            }
        }

        /**
         * Sets the maximum time (ms) before the screen auto-locks after sleep.
         *
         * @return `true` if the policy was applied.
         */
        fun setMaxScreenLockTimeout(context: Context, timeoutMs: Long): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            return if (dpm.isAdminActive(getComponentName(context))) {
                dpm.setMaximumTimeToLock(getComponentName(context), timeoutMs)
                Log.i(TAG, "Max screen lock timeout set to ${timeoutMs}ms")
                true
            } else {
                Log.w(TAG, "Cannot set lock timeout — Device Admin not active")
                false
            }
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled")
        Toast.makeText(context, "OmniDev Device Admin activated ✓", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin disabled")
        Toast.makeText(context, "OmniDev Device Admin deactivated", Toast.LENGTH_SHORT).show()
    }

    override fun onPasswordChanged(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordChanged(context, intent, user)
        Log.d(TAG, "Device password changed")
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordFailed(context, intent, user)
        // This is a great place to trigger the AI agent to take a silent screenshot from the front camera
        // using the OmniScreenCaptureService or Camera API, to catch whoever is trying to unlock the phone!
        Log.w(TAG, "Password attempt failed")
    }
}
