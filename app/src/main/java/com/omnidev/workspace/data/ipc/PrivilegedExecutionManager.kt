package com.omnidev.workspace.data.ipc

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.ShizukuResult

/**
 * PrivilegedExecutionManager — النسخة المُصلَحة والمُحسَّنة.
 *
 * ### Execution Backends (بالأولوية)
 * 1. **Shizuku.newProcess()** — `ShizukuCommandTool.execute()` — shell UID
 * 2. **rish via Shizuku** — `RishShellManager.execute()` — full ADB-equivalent
 * 3. **Root/SU** — `executeViaRoot()` — root shell
 *
 * ### الإصلاحات
 * 1. معالجة صحيحة لـ `ShizukuResult.PartialSuccess`:
 *    - في output مفيد (exit != 127) → نجاح
 *    - فارغ أو command not found → fallback
 * 2. رسائل تشخيصية أوضح عند الفشل
 * 3. `getTermuxBootstrapHints()` — تعليمات تثبيت Termux بدون Termux
 * 4. `installTermuxViaShizuku()` — تحميل وتثبيت Termux APK عبر Shizuku
 */
object PrivilegedExecutionManager {

    private const val TAG = "PrivMgr"
    private const val MAX_OUTPUT = 8_000

    @Volatile private var rishManager: RishShellManager? = null
    private val rishInitLock = Any()

    // ──────────────────────────────────────────────────────────────
    // Initialization
    // ──────────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (rishManager == null) {
            synchronized(rishInitLock) {
                if (rishManager == null) {
                    rishManager = RishShellManager(context.applicationContext)
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Status
    // ──────────────────────────────────────────────────────────────

    fun getRishManager(): RishShellManager? = rishManager
    fun isShizukuReady(): Boolean =
        ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission()
    fun isRishReady(): Boolean = rishManager?.isAvailable() ?: false
    fun isRootAvailable(): Boolean = runCatching {
        Runtime.getRuntime().exec(arrayOf("which", "su")).waitFor() == 0
    }.getOrDefault(false)

    // ──────────────────────────────────────────────────────────────
    // executeCommand — دالة التنفيذ الجوهرية
    // ──────────────────────────────────────────────────────────────

    /**
     * ينفّذ [command] عبر أفضل backend متاح.
     *
     * ### Fallback Chain
     * Shizuku → rish → Root → Failure (مع تشخيص واضح)
     */
    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        if (command.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("الأمر فارغ."))
        }

        // ── 1. Shizuku.newProcess() (الأفضل — shell UID) ──
        if (ShizukuCommandTool.isAvailable()) {
            when (val r = ShizukuCommandTool.execute(command)) {
                is ShizukuResult.Success -> {
                    Log.d(TAG, "Shizuku ✅ exit=0: ${command.take(40)}")
                    return@withContext Result.success(r.output.trim().take(MAX_OUTPUT))
                }
                is ShizukuResult.PartialSuccess -> {
                    val output = r.output.trim()
                    if (output.isNotBlank() && output != "(no output)" && r.exitCode != 127) {
                        // في output مفيد حتى لو exit != 0 (مثل grep، diff، etc.)
                        Log.d(TAG, "Shizuku ✅ exit=${r.exitCode} (partial OK): ${command.take(40)}")
                        return@withContext Result.success(output.take(MAX_OUTPUT))
                    } else {
                        Log.w(TAG, "Shizuku PartialSuccess بدون output (exit=${r.exitCode}) → fallback: ${command.take(40)}")
                    }
                }
                is ShizukuResult.PermissionRequired ->
                    Log.w(TAG, "Shizuku بدون إذن → fallback: ${r.message}")
                is ShizukuResult.Unavailable ->
                    Log.w(TAG, "Shizuku غير متاح → fallback: ${r.message}")
                is ShizukuResult.Failure ->
                    Log.w(TAG, "Shizuku فشل → fallback: ${r.reason}")
            }
        } else {
            Log.d(TAG, "Shizuku غير نشط → جرب rish/root")
        }

        // ── 2. rish (full ADB-equivalent shell) ──
        if (isRishReady()) {
            Log.d(TAG, "Trying rish: ${command.take(40)}")
            val rishResult = rishManager!!.execute(command)
            if (rishResult.isSuccess) {
                val out = rishResult.getOrThrow()
                Log.d(TAG, "rish ✅: ${command.take(40)}")
                return@withContext Result.success(out.take(MAX_OUTPUT))
            }
            Log.w(TAG, "rish فشل: ${rishResult.exceptionOrNull()?.message}")
        }

        // ── 3. Root/SU ──
        if (isRootAvailable()) {
            Log.d(TAG, "Trying root: ${command.take(40)}")
            return@withContext executeViaRoot(command)
        }

        // ── Failure: تشخيص واضح ──
        Result.failure(
            IllegalStateException(buildFailureMessage())
        )
    }

    /**
     * بناء رسالة فشل تشخيصية تشرح لماذا فشل التنفيذ وما الحل.
     */
    private fun buildFailureMessage(): String = buildString {
        appendLine("❌ لا يوجد execution backend متاح.")
        appendLine()
        val shizukuAvail = ShizukuCommandTool.isAvailable()
        val shizukuPerm  = if (shizukuAvail) ShizukuCommandTool.hasPermission() else false
        appendLine("• Shizuku: ${when {
            !shizukuAvail -> "❌ غير متاح (Shizuku غير مشغّل)"
            !shizukuPerm  -> "⚠️ متاح لكن بدون إذن"
            else          -> "⚠️ متاح لكن فشل التنفيذ"
        }}")
        appendLine("• rish: ${if (isRishReady()) "⚠️ متاح لكن فشل" else "❌ غير متاح"}")
        appendLine("• root: ❌ غير متاح")
        appendLine()
        appendLine("الحل:")
        if (!shizukuAvail) {
            appendLine("  1. افتح تطبيق Shizuku وشغّله")
        } else if (!shizukuPerm) {
            appendLine("  1. افتح Shizuku → امنح الإذن للتطبيق")
        }
        appendLine("  2. شغّل: execution_diagnostics action=fix_shizuku")
    }

    // ──────────────────────────────────────────────────────────────
    // System info helpers
    // ──────────────────────────────────────────────────────────────

    suspend fun getDeviceState(context: Context): DeviceStateSnapshot =
        withContext(Dispatchers.IO) {
            val foregroundPkg = getForegroundPackage()
            val batteryLevel  = getBatteryLevel()
            val totalRamMb    = getTotalRamMb()
            DeviceStateSnapshot(
                buildFingerprint  = Build.FINGERPRINT,
                sdkInt            = Build.VERSION.SDK_INT,
                model             = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion    = Build.VERSION.RELEASE,
                shizukuReady      = isShizukuReady(),
                rishAvailable     = isRishReady(),
                rootAvailable     = isRootAvailable(),
                foregroundPackage = foregroundPkg,
                batteryLevel      = batteryLevel,
                totalRamMb        = totalRamMb
            )
        }

    suspend fun getSystemProperty(key: String): Result<String> =
        executeCommand("getprop ${sanitizeArgument(key)}")

    suspend fun setSystemProperty(key: String, value: String): Result<String> =
        executeCommand("setprop ${sanitizeArgument(key)} ${sanitizeArgument(value)}")

    suspend fun dumpSysInfo(service: String): Result<String> {
        val safe = service.replace(Regex("[^a-zA-Z0-9._/\\-]"), "").take(64)
        if (safe.isEmpty()) return Result.failure(IllegalArgumentException("اسم service غير صحيح."))
        return executeCommand("dumpsys $safe").map { it.take(MAX_OUTPUT) }
    }

    suspend fun listRunningProcesses(): Result<String> =
        executeCommand("ps -A").map { it.take(MAX_OUTPUT) }

    suspend fun readSetting(namespace: String, key: String): Result<String> {
        val ns = validateSettingsNamespace(namespace)
            ?: return Result.failure(IllegalArgumentException("namespace غير صحيح. استخدم: system, secure, global."))
        val safeKey = key.replace(Regex("[^a-zA-Z0-9_.]"), "")
        if (safeKey.isEmpty()) return Result.failure(IllegalArgumentException("key غير صحيح."))
        return executeCommand("settings get $ns $safeKey")
    }

    suspend fun writeSetting(namespace: String, key: String, value: String): Result<String> {
        val ns = validateSettingsNamespace(namespace)
            ?: return Result.failure(IllegalArgumentException("namespace غير صحيح."))
        val safeKey   = key.replace(Regex("[^a-zA-Z0-9_.]"), "")
        val safeValue = value.replace(Regex("[;&|`\$\\\\\n\r]"), "")
        if (safeKey.isEmpty()) return Result.failure(IllegalArgumentException("key غير صحيح."))
        return executeCommand("settings put $ns $safeKey $safeValue")
    }

    suspend fun listSettings(namespace: String): Result<String> {
        val ns = validateSettingsNamespace(namespace)
            ?: return Result.failure(IllegalArgumentException("namespace غير صحيح."))
        return executeCommand("settings list $ns").map { it.take(MAX_OUTPUT) }
    }

    suspend fun queryPackages(filter: String): Result<String> {
        val safeFilter = filter.replace(Regex("[^a-zA-Z0-9._]"), "")
        val cmd = if (safeFilter.isNotEmpty()) "pm list packages | grep -F $safeFilter"
                  else "pm list packages"
        return executeCommand(cmd).map { it.take(MAX_OUTPUT) }
    }

    suspend fun installApk(apkPath: String): Result<String> {
        val safePath = apkPath.replace(Regex("[^a-zA-Z0-9_.\\-/]"), "")
        if (!safePath.endsWith(".apk")) {
            return Result.failure(IllegalArgumentException("المسار يجب أن ينتهي بـ .apk"))
        }
        return executeCommand("pm install -r -g $safePath")
    }

    suspend fun uninstallPackage(packageName: String): Result<String> {
        val safePkg = sanitizePackageName(packageName)
            ?: return Result.failure(IllegalArgumentException("اسم الحزمة غير صحيح."))
        return executeCommand("pm uninstall $safePkg")
    }

    suspend fun grantPermission(packageName: String, permission: String): Result<String> {
        val safePkg  = sanitizePackageName(packageName) ?: return Result.failure(IllegalArgumentException("اسم الحزمة غير صحيح."))
        val safePerm = permission.replace(Regex("[^a-zA-Z0-9._]"), "")
        return executeCommand("pm grant $safePkg $safePerm")
    }

    suspend fun revokePermission(packageName: String, permission: String): Result<String> {
        val safePkg  = sanitizePackageName(packageName) ?: return Result.failure(IllegalArgumentException("اسم الحزمة غير صحيح."))
        val safePerm = permission.replace(Regex("[^a-zA-Z0-9._]"), "")
        return executeCommand("pm revoke $safePkg $safePerm")
    }

    suspend fun getPackageInfo(packageName: String): Result<String> {
        val safePkg = sanitizePackageName(packageName) ?: return Result.failure(IllegalArgumentException("اسم الحزمة غير صحيح."))
        return executeCommand("pm dump $safePkg").map { it.take(MAX_OUTPUT) }
    }

    suspend fun forceStopApp(packageName: String): Result<String> {
        val safePkg = sanitizePackageName(packageName) ?: return Result.failure(IllegalArgumentException("اسم الحزمة غير صحيح."))
        return executeCommand("am force-stop $safePkg")
    }

    suspend fun launchComponent(component: String): Result<String> {
        if (component.isBlank()) return Result.failure(IllegalArgumentException("component فارغ."))
        val safeComponent = component.replace(Regex("[;&|`\$\\\\\n\r]"), "").take(256)
        return executeCommand("am start $safeComponent")
    }

    suspend fun sendBroadcast(action: String): Result<String> {
        val safeAction = action.replace(Regex("[^a-zA-Z0-9._]"), "")
        if (safeAction.isEmpty()) return Result.failure(IllegalArgumentException("action غير صحيح."))
        return executeCommand("am broadcast -a $safeAction")
    }

    suspend fun injectTap(x: Int, y: Int): Result<String> =
        executeCommand("input tap $x $y")

    suspend fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Result<String> =
        executeCommand("input swipe $x1 $y1 $x2 $y2 $durationMs")

    suspend fun injectText(text: String): Result<String> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("النص فارغ."))
        return executeCommand("input text ${sanitizeArgument(text)}")
    }

    suspend fun injectKeyEvent(keyCode: Int): Result<String> =
        executeCommand("input keyevent $keyCode")

    suspend fun captureScreen(outputPath: String): Result<String> {
        val safePath = outputPath.replace(Regex("[^a-zA-Z0-9_.\\-/]"), "")
        if (safePath.isEmpty()) return Result.failure(IllegalArgumentException("مسار غير صحيح."))
        return executeCommand("screencap -p $safePath").map { safePath }
    }

    suspend fun controlService(service: String, action: String): Result<String> {
        val safeService = service.lowercase().replace(Regex("[^a-z]"), "")
        val safeAction  = action.lowercase().replace(Regex("[^a-z]"), "")
        if (safeService !in setOf("wifi", "data", "bluetooth", "nfc", "power")) {
            return Result.failure(IllegalArgumentException("service غير صحيح."))
        }
        if (safeAction !in setOf("enable", "disable")) {
            return Result.failure(IllegalArgumentException("action يجب أن يكون 'enable' أو 'disable'."))
        }
        return executeCommand("svc $safeService $safeAction")
    }

    suspend fun windowManager(subCommand: String, value: String): Result<String> {
        val safeSub = subCommand.lowercase().replace(Regex("[^a-z ]"), "").trim()
        if (safeSub !in setOf("size", "density", "size reset", "density reset")) {
            return Result.failure(IllegalArgumentException("subcommand غير صحيح."))
        }
        val cmd = if (value.isBlank()) "wm $safeSub"
                  else "wm $safeSub ${value.replace(Regex("[^0-9x]"), "")}"
        return executeCommand(cmd)
    }

    // ──────────────────────────────────────────────────────────────
    // Termux bootstrap (تثبيت Termux بدون Termux)
    // ──────────────────────────────────────────────────────────────

    /**
     * يولِّد تعليمات تثبيت Termux بدون الحاجة لمتجر.
     * الوكيل يستطيع تنفيذ هذه الخطوات عبر Shizuku.
     */
    fun getTermuxBootstrapHints(): String = buildString {
        appendLine("=== تثبيت Termux عبر Shizuku (بدون متجر) ===")
        appendLine()
        appendLine("الطريقة 1 — تحميل APK وتثبيته عبر pm install:")
        appendLine("""  # تحميل Termux APK من F-Droid""")
        appendLine("""  curl -L "https://f-droid.org/repo/com.termux_118.apk" \\""")
        appendLine("""       -o /data/local/tmp/termux.apk""")
        appendLine("""  # أو wget:""")
        appendLine("""  wget -O /data/local/tmp/termux.apk \\""")
        appendLine("""       "https://f-droid.org/repo/com.termux_118.apk"""")
        appendLine("""  # تثبيت بصلاحيات shell:""")
        appendLine("""  pm install -r -g /data/local/tmp/termux.apk""")
        appendLine()
        appendLine("الطريقة 2 — استخدام agent_runtime action=tool_bootstrap:")
        appendLine("""  agent_runtime action=tool_bootstrap tool=termux""")
        appendLine()
        appendLine("الطريقة 3 — تثبيت يدوي من F-Droid:")
        appendLine("""  https://f-droid.org/en/packages/com.termux/""")
        appendLine()
        appendLine("بعد التثبيت شغّل في Termux:")
        appendLine("""  pkg update && pkg upgrade -y""")
        appendLine("""  pkg install -y python git curl wget nodejs""")
    }

    /**
     * يحاول تحميل وتثبيت Termux APK عبر Shizuku.
     * يحتاج curl أو wget متاح عبر Shizuku.
     */
    suspend fun installTermuxViaShizuku(): Result<String> = withContext(Dispatchers.IO) {
        if (!isShizukuReady()) {
            return@withContext Result.failure(IllegalStateException("Shizuku غير متاح."))
        }

        val apkUrl = "https://f-droid.org/repo/com.termux_118.apk"
        val apkPath = "/data/local/tmp/termux_install.apk"
        val sb = StringBuilder()

        // تحقق من وجود downloader
        val hasCurl = executeCommand("which curl 2>/dev/null").getOrNull()?.startsWith("/") == true
        val hasWget = executeCommand("which wget 2>/dev/null").getOrNull()?.startsWith("/") == true

        if (!hasCurl && !hasWget) {
            return@withContext Result.failure(
                IllegalStateException(
                    "لا يوجد curl أو wget. استخدم agent_runtime action=tool_bootstrap tool=termux\n" +
                    "أو ثبّت Termux يدوياً من F-Droid."
                )
            )
        }

        // تحميل APK
        sb.appendLine("⬇️ تحميل Termux APK...")
        val downloadCmd = if (hasCurl) {
            "curl -L -o '$apkPath' '$apkUrl' 2>&1 && echo DOWNLOAD_OK"
        } else {
            "wget -O '$apkPath' '$apkUrl' 2>&1 && echo DOWNLOAD_OK"
        }

        val downloadResult = executeCommand(downloadCmd)
        if (downloadResult.isFailure || downloadResult.getOrDefault("").contains("DOWNLOAD_OK").not()) {
            return@withContext Result.failure(RuntimeException("فشل التحميل: ${downloadResult.exceptionOrNull()?.message}"))
        }
        sb.appendLine("✅ تم التحميل")

        // تثبيت APK
        sb.appendLine("📦 تثبيت Termux...")
        val installResult = executeCommand("pm install -r -g '$apkPath'")
        val installOut = installResult.getOrNull() ?: installResult.exceptionOrNull()?.message ?: ""
        sb.appendLine(installOut)

        if (installOut.contains("Success", ignoreCase = true)) {
            sb.appendLine("✅ تم تثبيت Termux بنجاح!")
            sb.appendLine("افتح Termux وشغّل: pkg update && pkg install python git")
            Result.success(sb.toString())
        } else {
            Result.failure(RuntimeException("فشل التثبيت: $installOut"))
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────

    private fun executeViaRoot(command: String): Result<String> = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

        val stdoutBuf = StringBuffer()
        val stderrBuf = StringBuffer()

        val sout = Thread {
            try { process.inputStream.bufferedReader().use { r ->
                r.lineSequence().forEach { if (stdoutBuf.length < MAX_OUTPUT) stdoutBuf.appendLine(it) }
            }} catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        val serr = Thread {
            try { process.errorStream.bufferedReader().use { r ->
                r.lineSequence().forEach { if (stderrBuf.length < 3_000) stderrBuf.appendLine(it) }
            }} catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        val wait = Thread {
            try { process.waitFor() } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }

        wait.join(30_000L)
        if (wait.isAlive) {
            process.destroy(); sout.interrupt(); serr.interrupt()
            throw RuntimeException("Root command تجاوز 30s")
        }
        sout.join(2_000L); serr.join(2_000L)

        val stdout = stdoutBuf.toString().trim()
        val stderr = stderrBuf.toString().trim()
        val exit   = process.exitValue()

        val output = when {
            stdout.isNotBlank() && stderr.isNotBlank() -> "$stdout\n[stderr]: $stderr"
            stdout.isNotBlank() -> stdout
            stderr.isNotBlank() -> stderr
            else -> "(no output)"
        }

        if (exit == 0 || stdout.isNotBlank()) output.trim().ifBlank { "(no output)" }
        else throw RuntimeException("Root exited $exit:\n$output")
    }

    private suspend fun getForegroundPackage(): String = withContext(Dispatchers.IO) {
        runCatching {
            when (val r = ShizukuCommandTool.execute(
                "dumpsys activity activities | grep mResumedActivity | head -1"
            )) {
                is ShizukuResult.Success        -> r.output.trim()
                is ShizukuResult.PartialSuccess -> r.output.trim()
                else -> "unknown"
            }
        }.getOrDefault("unknown")
    }

    private suspend fun getBatteryLevel(): Int = withContext(Dispatchers.IO) {
        runCatching {
            when (val r = ShizukuCommandTool.execute("dumpsys battery | grep level | head -1")) {
                is ShizukuResult.Success, is ShizukuResult.PartialSuccess ->
                    Regex("level:\\s*(\\d+)").find(r.toDisplayString())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
                else -> -1
            }
        }.getOrDefault(-1)
    }

    private suspend fun getTotalRamMb(): Long = withContext(Dispatchers.IO) {
        runCatching {
            when (val r = ShizukuCommandTool.execute("cat /proc/meminfo | grep MemTotal | head -1")) {
                is ShizukuResult.Success, is ShizukuResult.PartialSuccess ->
                    Regex("(\\d+)").find(r.toDisplayString())?.groupValues?.getOrNull(1)
                        ?.toLongOrNull()?.let { it / 1024 } ?: -1L
                else -> -1L
            }
        }.getOrDefault(-1L)
    }

    private fun sanitizePackageName(name: String): String? {
        val safe = name.replace(Regex("[^a-zA-Z0-9._]"), "")
        return if (safe.isEmpty()) null else safe
    }

    private fun sanitizeArgument(arg: String): String = "'${arg.replace("'", "'\\''")}'"

    private fun validateSettingsNamespace(namespace: String): String? {
        val ns = namespace.lowercase()
        return if (ns in setOf("system", "secure", "global")) ns else null
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// DeviceStateSnapshot
// ──────────────────────────────────────────────────────────────────────────────
data class DeviceStateSnapshot(
    val buildFingerprint: String,
    val sdkInt: Int,
    val model: String,
    val androidVersion: String,
    val shizukuReady: Boolean,
    val rishAvailable: Boolean,
    val rootAvailable: Boolean,
    val foregroundPackage: String,
    val batteryLevel: Int,
    val totalRamMb: Long
) {
    fun toJson(): String = buildString {
        append("{")
        append("\"buildFingerprint\":\"${buildFingerprint.jsonEscape()}\",")
        append("\"sdkInt\":$sdkInt,")
        append("\"model\":\"${model.jsonEscape()}\",")
        append("\"androidVersion\":\"${androidVersion.jsonEscape()}\",")
        append("\"shizukuReady\":$shizukuReady,")
        append("\"rishAvailable\":$rishAvailable,")
        append("\"rootAvailable\":$rootAvailable,")
        append("\"foregroundPackage\":\"${foregroundPackage.jsonEscape()}\",")
        append("\"batteryLevel\":$batteryLevel,")
        append("\"totalRamMb\":$totalRamMb")
        append("}")
    }

    private fun String.jsonEscape(): String = this
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}
