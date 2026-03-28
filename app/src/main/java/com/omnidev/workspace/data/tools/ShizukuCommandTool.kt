package com.omnidev.workspace.data.tools

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * ShizukuCommandTool — النسخة المُصلَحة جذرياً.
 *
 * ### الإصلاحات الجوهرية في هذه النسخة
 *
 * **Bug #1 — Reflection يسبب NoSuchMethodException / InvocationTargetException**
 * الكود القديم:
 * ```kotlin
 * val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
 * val newProcessMethod = shizukuClass.getMethod("newProcess", ...)
 * val process = newProcessMethod.invoke(null, ...) as Process
 * ```
 * سبب الفشل: `Shizuku.newProcess()` هي static method مكشوفة مباشرة في الـ library،
 * reflection يفشل لأن الـ method signature تتغير بين الإصدارات، أو لأن
 * `Class.forName()` يبحث في classloader غلط على بعض الـ ROM.
 * الإصلاح: استدعاء `Shizuku.newProcess()` مباشرة → لا reflection على الإطلاق.
 *
 * **Bug #2 — pipe-buffer deadlock محتمل**
 * القديم كان يبدأ stdout/stderr threads وبعدين waitFor.
 * الإصلاح: threads تبدأ قبل waitFor وبعدين join — نفس التسلسل لكن صحيح.
 *
 * **Bug #3 — output فارغ بسبب stream reading بعد process.waitFor()**
 * بعض الـ streams تُغلق عند waitFor قبل ما threads تقرأها.
 * الإصلاح: threads تبدأ أولاً، ثم waitFor thread، ثم join بالترتيب الصحيح.
 *
 * **Bug #4 — PartialSuccess.output كان رسالة خطأ مش actual stdout**
 * الإصلاح: output = normalizedOutput دائماً.
 */
object ShizukuCommandTool {

    private const val TAG = "ShizukuCommandTool"
    private const val SHIZUKU_CODE = 1001
    private const val MAX_OUTPUT_CHARS = 8_000
    private const val MAX_STDERR_CHARS = 3_000
    private const val COMMAND_TIMEOUT_MS = 30_000L
    private const val PERMISSION_WAIT_MS = 12_000L
    private const val PERMISSION_POLL_MS = 200L

    const val SHIZUKU_UNAVAILABLE_ERROR: String =
        "Shizuku غير متاح أو غير مُصرَّح. تأكد من تشغيل Shizuku وإعطاء الصلاحية."

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    suspend fun execute(command: String): ShizukuResult {
        return withContext(Dispatchers.IO) {
            if (command.isBlank()) {
                return@withContext ShizukuResult.Failure("الأمر فارغ.")
            }

            if (!isAvailable()) {
                return@withContext ShizukuResult.Unavailable(SHIZUKU_UNAVAILABLE_ERROR)
            }

            // انتظر الإذن إذا لم يكن ممنوحاً
            if (!hasPermission()) {
                requestAndWaitPermission()
                if (!hasPermission()) {
                    Log.w(TAG, "Shizuku permission denied after wait")
                    return@withContext ShizukuResult.PermissionRequired(
                        "يحتاج إذن Shizuku. افتح تطبيق Shizuku واضغط 'منح الإذن'."
                    )
                }
            }

            // حاول حتى 3 مرات مع exponential backoff
            var lastResult: ShizukuResult = ShizukuResult.Failure("لم يبدأ التنفيذ")
            for (attempt in 0 until 3) {
                lastResult = executeOnce(command)
                when (lastResult) {
                    is ShizukuResult.Success,
                    is ShizukuResult.PartialSuccess   -> return@withContext lastResult
                    is ShizukuResult.PermissionRequired,
                    is ShizukuResult.Unavailable      -> return@withContext lastResult
                    is ShizukuResult.Failure -> {
                        val msg = (lastResult as ShizukuResult.Failure).reason
                        // أعِد المحاولة فقط لأخطاء Shizuku service المؤقتة
                        if (!isRetryableError(msg) || attempt >= 2) {
                            return@withContext lastResult
                        }
                        Log.w(TAG, "Shizuku retry ${attempt + 1}/3: $msg")
                        delay(500L * (1L shl attempt))   // 500ms → 1s → 2s
                    }
                }
            }
            lastResult
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Core execution — FIXED: Direct Shizuku.newProcess() call
    // ──────────────────────────────────────────────────────────────

    private fun executeOnce(command: String): ShizukuResult {
        return try {
            // *** الإصلاح الجذري: استدعاء مباشر بدون reflection ***
            // Shizuku.newProcess() مشابه لـ Runtime.exec() لكن يشغّل بـ shell UID
            val process: Process = Shizuku.newProcess(
                arrayOf("sh", "-c", command),   // cmd array
                null,                            // env (null = inherit)
                null                             // workdir (null = /system/bin)
            )

            readProcessOutput(process, command)

        } catch (e: SecurityException) {
            // إذن مرفوض
            ShizukuResult.PermissionRequired(
                "Shizuku رفض الأذن: ${e.message}"
            )
        } catch (e: Throwable) {
            val rootCause = unwrapCause(e)
            Log.e(TAG, "Shizuku.newProcess() failed: ${rootCause.javaClass.name}: ${rootCause.message}")
            ShizukuResult.Failure("خطأ في Shizuku: ${rootCause.message?.take(200)}")
        }
    }

    /**
     * يقرأ stdout/stderr من الـ process بشكل آمن مع timeout.
     * يبدأ threads للقراءة قبل waitFor لتفادي pipe-buffer deadlock.
     */
    private fun readProcessOutput(process: Process, command: String): ShizukuResult {
        val stdoutBuf = StringBuffer()
        val stderrBuf = StringBuffer()

        // *** الإصلاح: ابدأ القراءة أولاً قبل waitFor لتفادي deadlock ***
        val stdoutThread = Thread {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buf = CharArray(4096)
                    var read: Int
                    while (reader.read(buf).also { read = it } != -1) {
                        if (stdoutBuf.length < MAX_OUTPUT_CHARS) {
                            stdoutBuf.append(buf, 0, read)
                        }
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        val stderrThread = Thread {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buf = CharArray(4096)
                    var read: Int
                    while (reader.read(buf).also { read = it } != -1) {
                        if (stderrBuf.length < MAX_STDERR_CHARS) {
                            stderrBuf.append(buf, 0, read)
                        }
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        // انتظر انتهاء الـ process مع timeout
        val waitThread = Thread {
            try { process.waitFor() } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }

        waitThread.join(COMMAND_TIMEOUT_MS)
        if (waitThread.isAlive) {
            process.destroy()
            stdoutThread.interrupt()
            stderrThread.interrupt()
            return ShizukuResult.Failure(
                "انتهى وقت التنفيذ (${COMMAND_TIMEOUT_MS / 1000}s): ${command.take(80)}"
            )
        }

        // انتظر اكتمال القراءة (timeout قصير)
        stdoutThread.join(3_000L)
        stderrThread.join(3_000L)

        val stdout = stdoutBuf.toString().trim()
        val stderr = stderrBuf.toString().trim()
        val exit = runCatching { process.exitValue() }.getOrDefault(-1)

        // دمج الـ output بذكاء: stdout أولاً، stderr كـ supplementary
        val actualOutput = when {
            stdout.isNotBlank() && stderr.isNotBlank() ->
                "$stdout\n[stderr]: ${stderr.take(500)}"
            stdout.isNotBlank() -> stdout
            stderr.isNotBlank() -> stderr
            else -> "(no output)"
        }.take(MAX_OUTPUT_CHARS)

        Log.d(TAG, "exit=$exit stdout=${stdout.length}chars stderr=${stderr.length}chars")

        return when {
            exit == 0 ->
                ShizukuResult.Success(actualOutput.ifBlank { "(no output)" })
            exit == 127 || (stderr.contains("not found", ignoreCase = true) && stdout.isBlank()) ->
                ShizukuResult.Failure("الأمر غير موجود (exit=$exit): ${stderr.take(200)}")
            stdout.isNotBlank() || stderr.isNotBlank() ->
                // exit != 0 لكن في output — PartialSuccess مع actual output
                ShizukuResult.PartialSuccess(
                    output = actualOutput,
                    exitCode = exit
                )
            else ->
                ShizukuResult.PartialSuccess(
                    output = "تنفيذ بدون output (exit=$exit)",
                    exitCode = exit
                )
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Status helpers
    // ──────────────────────────────────────────────────────────────

    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    // ──────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────

    private fun requestAndWaitPermission() {
        runCatching { Shizuku.requestPermission(SHIZUKU_CODE) }
        val deadline = System.currentTimeMillis() + PERMISSION_WAIT_MS
        while (!hasPermission() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(PERMISSION_POLL_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun unwrapCause(e: Throwable): Throwable =
        e.cause?.let { if (it != e) unwrapCause(it) else e } ?: e

    /**
     * هل الخطأ مؤقت ويستحق إعادة المحاولة؟
     * (أخطاء service بعيدة / DeadObject يمكن تجاوزها بـ retry)
     */
    private fun isRetryableError(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("deadobject") ||
               lower.contains("remoteexception") ||
               lower.contains("binder") ||
               lower.contains("transaction failed") ||
               lower.contains("service connection")
    }

    /**
     * هل الخطأ من نوع Shizuku service exception؟
     * (للتوافق مع الكود القديم)
     */
    fun isShizukuServiceException(error: Throwable): Boolean {
        val cause = unwrapCause(error)
        val name = cause.javaClass.name
        val message = (cause.message ?: error.message).orEmpty().lowercase()
        return name == "android.os.DeadObjectException" ||
               name == "android.os.RemoteException" ||
               cause is SecurityException ||
               (cause is IllegalStateException && message.contains("shizuku")) ||
               message.contains("rikka.shizuku") ||
               message.contains("binder") ||
               message.contains("transaction failed")
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// ShizukuResult sealed class
// ──────────────────────────────────────────────────────────────────────────────

sealed class ShizukuResult {

    /** نجح الأمر بـ exit 0 */
    data class Success(val output: String) : ShizukuResult()

    /**
     * الأمر انتهى بـ exit != 0 لكن في output مفيد.
     * output = actual stdout (مش رسالة خطأ مُنسَّقة).
     */
    data class PartialSuccess(val output: String, val exitCode: Int) : ShizukuResult()

    /** فشل كامل — لا output مفيد */
    data class Failure(val reason: String) : ShizukuResult()

    data class PermissionRequired(val message: String) : ShizukuResult()
    data class Unavailable(val message: String) : ShizukuResult()

    // ── helpers ──

    /** نص للعرض — دائماً يرجع actual output */
    fun toDisplayString(): String = when (this) {
        is Success          -> output
        is PartialSuccess   -> output
        is Failure          -> "خطأ: $reason"
        is PermissionRequired -> "يحتاج إذن: $message"
        is Unavailable      -> "غير متاح: $message"
    }

    /** هل في output مفيد؟ */
    fun hasUsefulOutput(): Boolean = when (this) {
        is Success        -> output.isNotBlank() && output != "(no output)"
        is PartialSuccess -> output.isNotBlank() && output != "(no output)"
        else              -> false
    }

    /** استخرج الـ output أو null */
    fun outputOrNull(): String? = when (this) {
        is Success        -> output.takeIf { it.isNotBlank() && it != "(no output)" }
        is PartialSuccess -> output.takeIf { it.isNotBlank() && it != "(no output)" }
        else              -> null
    }
}
