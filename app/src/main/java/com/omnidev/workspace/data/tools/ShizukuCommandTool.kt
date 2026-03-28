package com.omnidev.workspace.data.tools

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.lang.reflect.InvocationTargetException

/**
 * ShizukuCommandTool — النسخة المُصلَحة.
 *
 * Bug fixes:
 * 1. PartialSuccess.output كان يحتوي على رسالة مُنسَّقة (failureMessage) بدل actual stdout.
 *    → الآن output = normalizedOutput دائماً
 * 2. أُضيف smart retry مع exponential backoff + permission wait
 * 3. أُضيف stderr capture كـ supplementary data
 * 4. تحسين stream reading لتفادي pipe-buffer deadlock بشكل أقوى
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

    suspend fun execute(command: String): ShizukuResult {
        return withContext(Dispatchers.IO) {
            if (command.isBlank()) {
                return@withContext ShizukuResult.Failure("الأمر فارغ.")
            }

            if (!isAvailable()) {
                return@withContext ShizukuResult.Unavailable(SHIZUKU_UNAVAILABLE_ERROR)
            }

            // انتظر الإذن إذا لزم
            if (!hasPermission()) {
                Shizuku.requestPermission(SHIZUKU_CODE)
                val deadline = System.currentTimeMillis() + PERMISSION_WAIT_MS
                while (!hasPermission() && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(PERMISSION_POLL_MS) } catch (_: InterruptedException) {}
                }
                if (!hasPermission()) {
                    Log.w(TAG, "Shizuku permission denied after wait")
                    return@withContext ShizukuResult.PermissionRequired(
                        "يحتاج إذن Shizuku. اضغط على طلب الإذن في تطبيق Shizuku."
                    )
                }
            }

            // حاول حتى 3 مرات مع backoff
            var lastResult: ShizukuResult = ShizukuResult.Failure("لم يبدأ التنفيذ")
            for (attempt in 0 until 3) {
                lastResult = executeOnce(command)
                when (lastResult) {
                    is ShizukuResult.Success,
                    is ShizukuResult.PartialSuccess -> return@withContext lastResult
                    is ShizukuResult.PermissionRequired,
                    is ShizukuResult.Unavailable -> return@withContext lastResult
                    is ShizukuResult.Failure -> {
                        val msg = (lastResult as ShizukuResult.Failure).reason
                        if (!isShizukuServiceException(Exception(msg)) || attempt >= 2) {
                            return@withContext lastResult
                        }
                        Log.w(TAG, "Shizuku retry ${attempt + 1}: $msg")
                        delay(500L * (1L shl attempt))
                    }
                }
            }
            lastResult
        }
    }

    private fun executeOnce(command: String): ShizukuResult {
        return try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = shizukuClass.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            val stdoutBuffer = StringBuffer()
            val stderrBuffer = StringBuffer()

            val stdoutThread = Thread {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val buf = CharArray(4096)
                        var read: Int
                        while (reader.read(buf).also { read = it } != -1) {
                            if (stdoutBuffer.length < MAX_OUTPUT_CHARS) {
                                stdoutBuffer.append(buf, 0, read)
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
                            if (stderrBuffer.length < MAX_STDERR_CHARS) {
                                stderrBuffer.append(buf, 0, read)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            // timeout مع استخدام Thread.join API-24-compatible
            val waitThread = Thread {
                try { process.waitFor() } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }

            waitThread.join(COMMAND_TIMEOUT_MS)
            if (waitThread.isAlive) {
                process.destroy()
                stdoutThread.interrupt()
                stderrThread.interrupt()
                return ShizukuResult.Failure("انتهى الوقت (${COMMAND_TIMEOUT_MS / 1000}s): ${command.take(80)}")
            }

            stdoutThread.join(2_000L)
            stderrThread.join(2_000L)

            val stdout = stdoutBuffer.toString().trim()
            val stderr = stderrBuffer.toString().trim()
            val exit = process.exitValue()

            // *** الإصلاح الجوهري: أعطِ الـ actual output دائماً ***
            // دمج stdout + stderr بذكاء
            val actualOutput = when {
                stdout.isNotBlank() && stderr.isNotBlank() ->
                    "$stdout\n[stderr]: ${stderr.take(500)}"
                stdout.isNotBlank() -> stdout
                stderr.isNotBlank() -> stderr
                else -> "(no output)"
            }.take(MAX_OUTPUT_CHARS)

            if (exit == 0 || (stdout.isNotBlank() && exit != 127)) {
                // exit=127 = command not found → ده فشل حقيقي
                // أي exit آخر مع stdout → نجاح جزئي مع output
                if (exit == 0) {
                    ShizukuResult.Success(actualOutput.ifBlank { "(no output)" })
                } else {
                    // *** الإصلاح: output = actualOutput مش failureMessage ***
                    ShizukuResult.PartialSuccess(
                        output = actualOutput,
                        exitCode = exit
                    )
                }
            } else if (stderr.contains("not found", ignoreCase = true) || exit == 127) {
                ShizukuResult.Failure("الأمر غير موجود (exit $exit): $stderr")
            } else {
                ShizukuResult.PartialSuccess(
                    output = actualOutput.ifBlank { "تنفيذ بدون output (exit $exit)" },
                    exitCode = exit
                )
            }

        } catch (e: Throwable) {
            val cause = unwrapCause(e)
            if (isShizukuServiceException(cause)) {
                ShizukuResult.Failure("خطأ في Shizuku service: ${cause.message}")
            } else {
                ShizukuResult.Failure("استثناء: ${cause.message}")
            }
        }
    }

    private fun unwrapCause(e: Throwable): Throwable =
        if (e is InvocationTargetException) e.targetException ?: e.cause ?: e
        else e.cause ?: e

    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isShizukuServiceException(error: Throwable): Boolean {
        val cause = unwrapCause(error)
        val name = cause::class.java.name
        val message = (cause.message ?: error.message).orEmpty()
        return name == "android.os.DeadObjectException" ||
            name == "android.os.RemoteException" ||
            cause is NoSuchMethodException ||
            cause is ClassNotFoundException ||
            cause is NoClassDefFoundError ||
            (cause is SecurityException && message.contains("shizuku", ignoreCase = true)) ||
            (cause is IllegalStateException && message.contains("shizuku", ignoreCase = true)) ||
            message.contains("rikka.shizuku.Shizuku.newProcess", ignoreCase = true)
    }
}

sealed class ShizukuResult {
    /** نجح الأمر بـ exit 0 */
    data class Success(val output: String) : ShizukuResult()

    /**
     * الأمر عنده output لكن exit != 0.
     * *** FIXED: output = actual stdout/stderr مش formatted message ***
     */
    data class PartialSuccess(val output: String, val exitCode: Int) : ShizukuResult()

    /** فشل كامل بدون output مفيد */
    data class Failure(val reason: String) : ShizukuResult()

    data class PermissionRequired(val message: String) : ShizukuResult()
    data class Unavailable(val message: String) : ShizukuResult()

    fun toDisplayString(): String = when (this) {
        is Success -> output
        is PartialSuccess -> output   // *** الـ actual output مش رسالة خطأ ***
        is Failure -> "خطأ: $reason"
        is PermissionRequired -> "يحتاج إذن: $message"
        is Unavailable -> "غير متاح: $message"
    }

    /** هل النتيجة تحتوي output مفيد (حتى لو exit != 0)؟ */
    fun hasUsefulOutput(): Boolean = when (this) {
        is Success -> output.isNotBlank() && output != "(no output)"
        is PartialSuccess -> output.isNotBlank() && output != "(no output)"
        else -> false
    }

    /** استخرج الـ output بغض النظر عن النوع */
    fun outputOrNull(): String? = when (this) {
        is Success -> output.takeIf { it.isNotBlank() && it != "(no output)" }
        is PartialSuccess -> output.takeIf { it.isNotBlank() && it != "(no output)" }
        else -> null
    }
}
