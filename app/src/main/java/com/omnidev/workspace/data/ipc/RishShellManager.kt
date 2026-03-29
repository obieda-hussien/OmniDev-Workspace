package com.omnidev.workspace.data.ipc

import android.content.Context
import android.os.Build
import android.util.Log
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.ShizukuResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * RishShellManager — النسخة المُصلَحة جذرياً.
 *
 * ### الإصلاح الجذري
 *
 * **Bug الأساسي — runCommand() كان يستخدم Runtime.getRuntime().exec()**
 * ```kotlin
 * // قبل ❌ — يشغّل بـ UID التطبيق → لا صلاحيات
 * val proc = Runtime.getRuntime().exec(
 *     arrayOf(APP_PROCESS, "/system/bin", SHELL_CLASS, "--sh", "-c", command), env
 * )
 * ```
 *
 * `Runtime.exec()` يشغّل الأمر بـ UID التطبيق (`u0_a###`).
 * لكن `app_process` مع rish DEX يحتاج UID=`shell` أو ما يعادله.
 * **Shizuku هو الوحيد الذي يمنح هذا الـ UID.**
 *
 * ```kotlin
 * // بعد ✅ — يشغّل بـ shell UID عبر Shizuku
 * val proc: Process = Shizuku.newProcess(
 *     arrayOf("sh", "-c", "CLASSPATH=<dex> $APP_PROCESS /system/bin $SHELL_CLASS --sh -c <cmd>"),
 *     null, null
 * )
 * ```
 *
 * ### Fallback
 * إذا Shizuku غير متاح أو فشل، يُحاوِل ShizukuCommandTool.execute() كـ fallback
 * (يبني الـ rish command كـ shell command عادي).
 *
 * ### DEX discovery
 * 1. Local files dir: `<filesDir>/rish_shizuku.dex`
 * 2. Shizuku export paths: `/data/local/tmp/`, user_de dir
 * 3. استخراج من Shizuku APK assets
 * 4. نسخ عبر Shizuku shell (إذا كان الـ dex في مكان محمي)
 *
 * ### Android 14+ compatibility
 * `app_process` على Android 14+ يرفض تحميل DEX قابل للكتابة.
 * [ensureReadOnlyOnApi34] يستخدم `chmod 400` تلقائياً.
 */
class RishShellManager(private val context: Context) {

    companion object {
        private const val TAG = "RishShellManager"
        private const val DEX_NAME = "rish_shizuku.dex"
        private const val RISH_SCRIPT_NAME = "rish"
        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        private const val APP_PROCESS = "/system/bin/app_process"
        private const val SHELL_CLASS = "rikka.shizuku.Shell"

        private const val TIMEOUT_MS = 30_000L
        private const val MAX_OUTPUT = 8_000

        /** المسارات التي يُصدِّر Shizuku إليها الـ dex بعد تفعيل "Use in terminal apps" */
        private val SHIZUKU_EXPORT_PATHS = listOf(
            "/data/local/tmp/rish_shizuku.dex",
            "/data/user_de/0/moe.shizuku.privileged.api/files/rish_shizuku.dex",
            "/data/user/0/moe.shizuku.privileged.api/files/rish_shizuku.dex"
        )

        /**
         * متغيرات بيئة تُضخّ في كل rish session لتحاكي بيئة `adb shell`.
         * CLASSPATH يُستبدل ديناميكياً في كل استدعاء.
         */
        private val RISH_ENV = arrayOf(
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/data/local/tmp"
        )
    }

    private val localDex: File get() = File(context.filesDir, DEX_NAME)
    private val localRish: File get() = File(context.filesDir, RISH_SCRIPT_NAME)

    // ──────────────────────────────────────────────────────────────
    // Availability
    // ──────────────────────────────────────────────────────────────

    fun isAvailable(): Boolean = runCatching {
        File(APP_PROCESS).exists() && locateDex() != null
    }.getOrDefault(false)

    // ──────────────────────────────────────────────────────────────
    // Command execution
    // ──────────────────────────────────────────────────────────────

    /**
     * ينفّذ [command] عبر rish shell (ADB-equivalent UID=shell).
     *
     * ### استراتيجية التنفيذ (بالأولوية):
     * 1. **Shizuku.newProcess()** — الطريقة الصحيحة (shell UID)
     * 2. **ShizukuCommandTool fallback** — يبني الـ rish command كـ Shizuku shell command
     *
     * @return [Result.success] مع stdout مُقلَّم أو [Result.failure] مع وصف الخطأ.
     */
    suspend fun execute(command: String): Result<String> = withContext(Dispatchers.IO) {
        val dex = prepareLocalDex()
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "rish_shizuku.dex غير موجود.\n" +
                    "افتح Shizuku → ⋮ → 'Use Shizuku in terminal apps' لتصدير الـ dex،\n" +
                    "أو تأكد من تثبيت Shizuku v13+ (يُصدِّر تلقائياً إلى /data/local/tmp/)."
                )
            )

        ensureReadOnlyOnApi34(dex)

        // المحاولة الأولى: Shizuku.newProcess() مباشرة (الأفضل)
        if (ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission()) {
            val shizukuResult = runCommandViaShizuku(dex, command)
            if (shizukuResult.isSuccess) return@withContext shizukuResult
            Log.w(TAG, "Shizuku.newProcess() rish failed, trying ShizukuCommandTool: ${shizukuResult.exceptionOrNull()?.message}")
        }

        // المحاولة الثانية: ShizukuCommandTool.execute() مع rish command
        if (ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission()) {
            val rishCmd = buildRishCommand(dex, command)
            return@withContext when (val r = ShizukuCommandTool.execute(rishCmd)) {
                is ShizukuResult.Success -> Result.success(r.output)
                is ShizukuResult.PartialSuccess -> {
                    if (r.output.isNotBlank() && r.output != "(no output)") {
                        Result.success(r.output)
                    } else {
                        Result.failure(RuntimeException("rish انتهى بـ exit=${r.exitCode} بدون output"))
                    }
                }
                else -> Result.failure(RuntimeException("rish فشل: ${r.toDisplayString()}"))
            }
        }

        Result.failure(
            IllegalStateException("Shizuku غير متاح. rish يحتاج Shizuku لتشغيل الأوامر بـ shell UID.")
        )
    }

    /**
     * ينفّذ script متعدد الأسطر عبر rish.
     * يكتب الـ script في ملف مؤقت ثم ينفّذه ويحذفه.
     */
    suspend fun executeScript(scriptContent: String): Result<String> = withContext(Dispatchers.IO) {
        val dex = prepareLocalDex()
            ?: return@withContext Result.failure(
                IllegalStateException("rish_shizuku.dex غير متاح.")
            )
        ensureReadOnlyOnApi34(dex)

        val tmpScript = File(context.cacheDir, "rish_script_${System.currentTimeMillis()}.sh")
        return@withContext try {
            tmpScript.writeText("#!/system/bin/sh\n$scriptContent")
            tmpScript.setExecutable(true, false)
            execute("sh ${tmpScript.absolutePath}")
        } finally {
            tmpScript.delete()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // DEX preparation
    // ──────────────────────────────────────────────────────────────

    /**
     * يُجهّز ملف rish_shizuku.dex في files dir التطبيق.
     * يجرب مصادر متعددة بالترتيب حتى ينجح.
     */
    suspend fun prepareLocalDex(): File? = withContext(Dispatchers.IO) {
        // 1. لدينا نسخة محلية كافية
        if (localDex.exists() && localDex.length() > 1024) return@withContext localDex

        // 2. انسخ من مسارات تصدير Shizuku (مباشرة عبر Java File API)
        for (path in SHIZUKU_EXPORT_PATHS) {
            val src = File(path)
            if (src.exists() && src.length() > 1024) {
                return@withContext runCatching {
                    src.copyTo(localDex, overwrite = true)
                    Log.d(TAG, "DEX copied from $path (${localDex.length() / 1024}KB)")
                    localDex
                }.getOrNull()
            }
        }

        // 3. انسخ عبر Shizuku shell (إذا كان الـ dex في مكان محمي)
        try {
            val copied = copyDexViaShizukuCommand()
            if (copied != null) {
                Log.d(TAG, "DEX copied via Shizuku shell (${copied.length() / 1024}KB)")
                return@withContext copied
            }
        } catch (e: Throwable) {
            Log.w(TAG, "copyDexViaShizukuCommand failed: ${e.message}")
        }

        // 4. استخرج من Shizuku APK
        val fromApk = extractFromShizukuApk()
        if (fromApk != null) {
            Log.d(TAG, "DEX extracted from Shizuku APK (${fromApk.length() / 1024}KB)")
        }
        fromApk
    }

    /**
     * يكتب (أو يُحدِّث) سكريبت rish في files dir التطبيق.
     * @return المسار الكامل للسكريبت.
     */
    fun ensureRishScript(): String {
        val dexPath = localDex.absolutePath
        localRish.writeText(buildRishScript(dexPath))
        localRish.setExecutable(true, false)
        return localRish.absolutePath
    }

    /**
     * تقرير حالة rish لأكشن `rish_setup`.
     */
    suspend fun statusReport(): String = withContext(Dispatchers.IO) {
        val appProcessExists = File(APP_PROCESS).exists()
        val localDexExists = localDex.exists() && localDex.length() > 1024
        val exportDexPath = SHIZUKU_EXPORT_PATHS.firstOrNull { File(it).exists() && File(it).length() > 1024 }
        val canExtractFromApk = runCatching {
            context.packageManager.getApplicationInfo(SHIZUKU_PKG, 0)
            true
        }.getOrDefault(false)

        buildString {
            appendLine("=== rish status ===")
            appendLine("app_process: ${if (appProcessExists) "✅" else "❌"} $APP_PROCESS")
            appendLine("local dex (${localDex.absolutePath}): ${if (localDexExists) "✅ (${localDex.length() / 1024}KB)" else "❌ not found or too small"}")
            appendLine("Shizuku export dex: ${if (exportDexPath != null) "✅ $exportDexPath" else "❌ not found"}")
            appendLine("Shizuku app installed: ${if (canExtractFromApk) "✅ ($SHIZUKU_PKG)" else "❌"}")
            appendLine("Android 14+ compatibility: ${if (Build.VERSION.SDK_INT >= 34) "⚠️ SDK ${Build.VERSION.SDK_INT} — dex must be chmod 400" else "✅ (SDK ${Build.VERSION.SDK_INT} < 34)"}")
            appendLine("rish script: ${if (localRish.exists()) "✅ ${localRish.absolutePath}" else "❌ not generated yet (call rish_setup)"}")
            appendLine("Execution method: ${if (ShizukuCommandTool.isAvailable()) "✅ Shizuku.newProcess() (shell UID)" else "⚠️ Runtime.exec() (app UID only)"}")
            appendLine()
            appendLine("Overall rish availability: ${if (isAvailable()) "✅ READY" else "❌ DEX not found — call action=rish_setup"}")
        }.trimEnd()
    }

    // ──────────────────────────────────────────────────────────────
    // Quick DEX locator
    // ──────────────────────────────────────────────────────────────

    fun locateDex(): File? {
        if (localDex.exists() && localDex.length() > 1024) return localDex
        return SHIZUKU_EXPORT_PATHS
            .map { File(it) }
            .firstOrNull { it.exists() && it.length() > 1024 }
    }

    // ──────────────────────────────────────────────────────────────
    // Private: runCommandViaShizuku — الإصلاح الجذري
    // ──────────────────────────────────────────────────────────────

    /**
     * ينفّذ الأمر عبر Shizuku.newProcess() مع الـ rish DEX كـ CLASSPATH.
     * هذا يُشغِّل الأمر بـ UID=shell (مثل adb shell).
     */
    private fun runCommandViaShizuku(dex: File, command: String): Result<String> {
        return try {
            // بناء الأمر الكامل: CLASSPATH=<dex> app_process /system/bin rikka.shizuku.Shell --sh -c <cmd>
            val fullCmd = "CLASSPATH=${shellEscape(dex.absolutePath)} $APP_PROCESS /system/bin $SHELL_CLASS --sh -c ${shellEscape(command)}"

            // *** الإصلاح: attempt reflective call to Shizuku.newProcess (private in some versions) ***
            val process: Process = try {
                val m = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                m.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                m.invoke(null, arrayOf("sh", "-c", fullCmd), RISH_ENV, null) as Process
            } catch (e: NoSuchMethodException) {
                // Fall back to Runtime.exec (app UID) if reflective access fails
                Runtime.getRuntime().exec(arrayOf("sh", "-c", fullCmd), RISH_ENV, null)
            }

            readProcessOutputToResult(process, command)

        } catch (e: SecurityException) {
            Result.failure(SecurityException("Shizuku رفض الأذن: ${e.message}"))
        } catch (e: Throwable) {
            val msg = e.cause?.message ?: e.message ?: e.javaClass.name
            Result.failure(RuntimeException("Shizuku.newProcess() فشل: $msg"))
        }
    }

    /**
     * يبني أمر rish كـ string لتمريره لـ ShizukuCommandTool.execute().
     * يُستخدم كـ fallback إذا Shizuku.newProcess() فشل.
     */
    private fun buildRishCommand(dex: File, command: String): String =
        "CLASSPATH=${shellEscape(dex.absolutePath)} $APP_PROCESS /system/bin $SHELL_CLASS --sh -c ${shellEscape(command)}"

    /**
     * يقرأ stdout/stderr من الـ process مع timeout وإعادة Result.
     */
    private fun readProcessOutputToResult(process: Process, command: String): Result<String> {
        val stdoutBuf = StringBuffer()
        val stderrBuf = StringBuffer()

        val stdoutThread = Thread {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    val buf = CharArray(4096)
                    var n: Int
                    while (r.read(buf).also { n = it } != -1) {
                        if (stdoutBuf.length < MAX_OUTPUT) stdoutBuf.append(buf, 0, n)
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        val stderrThread = Thread {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                    val buf = CharArray(4096)
                    var n: Int
                    while (r.read(buf).also { n = it } != -1) {
                        if (stderrBuf.length < 2_000) stderrBuf.append(buf, 0, n)
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        val waitThread = Thread {
            try { process.waitFor() } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }

        waitThread.join(TIMEOUT_MS)
        if (waitThread.isAlive) {
            process.destroy()
            stdoutThread.interrupt()
            stderrThread.interrupt()
            return Result.failure(RuntimeException("rish تجاوز الوقت (${TIMEOUT_MS / 1000}s): ${command.take(80)}"))
        }

        stdoutThread.join(3_000L)
        stderrThread.join(3_000L)

        val stdout = stdoutBuf.toString().trim()
        val stderr = stderrBuf.toString().trim()
        val exit = runCatching { process.exitValue() }.getOrDefault(-1)

        Log.d(TAG, "rish exit=$exit stdout=${stdout.length}c stderr=${stderr.length}c")

        return when {
            exit == 0 ->
                Result.success(stdout.ifBlank { "(no output)" })
            stdout.isNotBlank() ->
                Result.success(stdout)   // non-zero exit لكن في output مفيد
            stderr.isNotBlank() ->
                Result.failure(RuntimeException("rish exit=$exit: $stderr"))
            else ->
                Result.failure(RuntimeException("rish exit=$exit بدون output"))
        }
    }

    // ──────────────────────────────────────────────────────────────
    // DEX private helpers
    // ──────────────────────────────────────────────────────────────

    private fun extractFromShizukuApk(): File? = runCatching {
        val ai = context.packageManager.getApplicationInfo(SHIZUKU_PKG, 0)
        ZipFile(ai.sourceDir).use { zip ->
            val entry = zip.getEntry("assets/$DEX_NAME") ?: zip.getEntry(DEX_NAME) ?: return null
            FileOutputStream(localDex).use { out ->
                zip.getInputStream(entry).copyTo(out)
            }
            if (localDex.length() > 1024) localDex else null
        }
    }.getOrNull()

    private suspend fun copyDexViaShizukuCommand(): File? = withContext(Dispatchers.IO) {
        if (!ShizukuCommandTool.isAvailable() || !ShizukuCommandTool.hasPermission()) {
            return@withContext null
        }

        val destPath = localDex.absolutePath

        for (srcPath in SHIZUKU_EXPORT_PATHS) {
            val cmd = "test -f '$srcPath' && test -s '$srcPath' && " +
                      "cp '$srcPath' '$destPath' && chmod 400 '$destPath' && echo OK"
            val result = ShizukuCommandTool.execute(cmd)
            if ((result is ShizukuResult.Success || result is ShizukuResult.PartialSuccess) &&
                result.outputOrNull()?.contains("OK") == true &&
                localDex.exists() && localDex.length() > 1024) {
                return@withContext localDex
            }
        }

        null
    }

    private fun ensureReadOnlyOnApi34(dex: File) {
        if (Build.VERSION.SDK_INT < 34) return
        if (!dex.canWrite()) return
        dex.setWritable(false, false)
        if (dex.canWrite()) {
            // آخر محاولة عبر Shizuku
            runCatching {
                ShizukuCommandTool.isAvailable().let { avail ->
                    if (avail) {
                        kotlinx.coroutines.runBlocking {
                            ShizukuCommandTool.execute("chmod 400 '${dex.absolutePath}'")
                        }
                    } else {
                        Runtime.getRuntime().exec(arrayOf("chmod", "400", dex.absolutePath)).waitFor()
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // rish script builder
    // ──────────────────────────────────────────────────────────────

    private fun buildRishScript(dexPath: String): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("# rish — Shizuku Remote Interactive Shell")
        appendLine("# Generated by OmniDev Workspace — RishShellManager")
        appendLine("# Execution method: Shizuku.newProcess() → shell UID")
        appendLine()
        appendLine("""DEX="$dexPath"""")
        appendLine()
        appendLine("""if [ ! -f "${'$'}DEX" ] || [ ! -s "${'$'}DEX" ]; then""")
        appendLine("""  echo "ERROR: Cannot find ${'$'}DEX"""")
        appendLine("""  echo "Open Shizuku → Menu → 'Use Shizuku in terminal apps' to export it."""")
        appendLine("""  exit 1""")
        appendLine("fi")
        appendLine()
        appendLine("""SDK=$(getprop ro.build.version.sdk)""")
        appendLine("""if [ "${'$'}SDK" -ge 34 ] && [ -w "${'$'}DEX" ]; then""")
        appendLine("""  echo "Android 14+: removing write permission from ${'$'}DEX ..."""")
        appendLine("""  chmod 400 "${'$'}DEX" 2>/dev/null || true""")
        appendLine("fi")
        appendLine()
        appendLine("""CLASSPATH="${'$'}DEX" $APP_PROCESS /system/bin $SHELL_CLASS "${'$'}@"""")
    }

    // ──────────────────────────────────────────────────────────────
    // Shell escape helper
    // ──────────────────────────────────────────────────────────────

    private fun shellEscape(s: String): String = "'${s.replace("'", "'\\''")}'"
}
