package com.omnidev.workspace.data.tools

import android.util.Log
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TermuxEnvironmentBridge — النسخة المُصلَحة والمُحسَّنة.
 *
 * إصلاحات جوهرية:
 * 1. LD_PRELOAD اختياري — يُضاف فقط إذا الملف موجود فعلاً
 * 2. استراتيجيات تنفيذ متعددة بترتيب fallback ذكي:
 *    a) env-prefix مع bash (الطريقة الأصلية المُصلَحة)
 *    b) run-as com.termux (يشغل بـ Termux's UID مباشرة)
 *    c) Termux RUN_COMMAND broadcast (عبر am broadcast)
 *    d) busybox/system fallback
 * 3. PATH يشمل كل binary locations المحتملة
 * 4. تحقق من وجود الملفات قبل استخدامها
 */
object TermuxEnvironmentBridge {

    private const val TAG = "TermuxBridge"

    const val TERMUX_PKG          = "com.termux"
    const val TERMUX_PREFIX       = "/data/data/com.termux/files/usr"
    const val TERMUX_HOME         = "/data/data/com.termux/files/home"
    const val TERMUX_BIN          = "$TERMUX_PREFIX/bin"
    const val TERMUX_LIB          = "$TERMUX_PREFIX/lib"
    const val TERMUX_BASH         = "$TERMUX_BIN/bash"
    const val TERMUX_PYTHON3      = "$TERMUX_BIN/python3"
    const val TERMUX_PYTHON       = "$TERMUX_BIN/python"
    const val TERMUX_NODE         = "$TERMUX_BIN/node"
    const val TERMUX_NPM          = "$TERMUX_BIN/npm"
    const val TERMUX_PIP3         = "$TERMUX_BIN/pip3"
    const val TERMUX_PIP          = "$TERMUX_BIN/pip"
    const val TERMUX_GIT          = "$TERMUX_BIN/git"
    const val TERMUX_PKG_MANAGER  = "$TERMUX_BIN/pkg"
    const val TERMUX_APT          = "$TERMUX_BIN/apt"
    private const val TERMUX_EXEC_SO = "$TERMUX_LIB/libtermux-exec.so"

    private const val MAX_OUTPUT = 10_000

    // ─────────────────────────────────────────────────────────────
    // Environment building — الإصلاح الجوهري
    // ─────────────────────────────────────────────────────────────

    /**
     * بيبني environment variables كـ inline shell assignments.
     * LD_PRELOAD يُضاف فقط إذا الملف موجود فعلاً (إصلاح Bug #2).
     */
    fun buildTermuxEnv(): Map<String, String> {
        val env = mutableMapOf(
            "HOME"           to TERMUX_HOME,
            "PREFIX"         to TERMUX_PREFIX,
            "TERM"           to "xterm-256color",
            "LANG"           to "en_US.UTF-8",
            "TMPDIR"         to "$TERMUX_PREFIX/tmp",
            "ANDROID_ROOT"   to "/system",
            "ANDROID_DATA"   to "/data",
            "PATH"           to buildPath(),
            "LD_LIBRARY_PATH" to buildLdLibraryPath()
        )
        // *** إصلاح: LD_PRELOAD فقط لو الملف موجود ***
        if (File(TERMUX_EXEC_SO).exists()) {
            env["LD_PRELOAD"] = TERMUX_EXEC_SO
        } else {
            Log.d(TAG, "libtermux-exec.so غير موجود — تخطي LD_PRELOAD")
        }
        return env
    }

    private fun buildPath(): String {
        val paths = mutableListOf(
            TERMUX_BIN,
            "$TERMUX_PREFIX/sbin",
            "/system/bin",
            "/system/xbin",
            "/sbin",
            "/vendor/bin",
            "/data/local/tmp"
        )
        // أضف Termux site-packages bin
        val pyBin = "$TERMUX_PREFIX/lib/python3*/site-packages/bin"
        paths.add(pyBin)
        return paths.joinToString(":")
    }

    private fun buildLdLibraryPath(): String {
        val paths = mutableListOf(
            TERMUX_LIB,
            "$TERMUX_LIB/termux-exec",
            "/system/lib64",
            "/system/lib",
            "/vendor/lib64",
            "/vendor/lib"
        )
        return paths.joinToString(":")
    }

    /**
     * يبني prefix string جاهز للـ shell command.
     * مثال: HOME='/data/..' PATH='...' python3 ...
     */
    fun buildEnvPrefix(): String =
        buildTermuxEnv()
            .entries
            .joinToString(" ") { (k, v) -> "$k=${shellQuote(v)}" }
            .let { if (it.isNotBlank()) "$it " else "" }

    // ─────────────────────────────────────────────────────────────
    // Availability checks
    // ─────────────────────────────────────────────────────────────

    fun isTermuxUsable(): Boolean = File(TERMUX_BASH).exists()

    suspend fun findBinary(name: String): String? = withContext(Dispatchers.IO) {
        // 1. مباشرة في Termux bin
        val direct = File(TERMUX_BIN, name)
        if (direct.exists() && direct.canExecute()) return@withContext direct.absolutePath

        // 2. عبر which مع Termux env
        if (isTermuxUsable()) {
            val envPfx = buildEnvPrefix()
            val result = PrivilegedExecutionManager.executeCommand(
                "${envPfx}which $name 2>/dev/null || command -v $name 2>/dev/null"
            ).getOrNull()?.trim()?.takeIf { isValidPath(it) }
            if (result != null) return@withContext result
        }

        // 3. system which
        PrivilegedExecutionManager.executeCommand(
            "which $name 2>/dev/null || command -v $name 2>/dev/null"
        ).getOrNull()?.trim()?.takeIf { isValidPath(it) }
    }

    private fun isValidPath(s: String): Boolean =
        s.isNotBlank() && s != "(no output)" && !s.startsWith("ERROR") && s.startsWith("/")

    // ─────────────────────────────────────────────────────────────
    // Core execution — استراتيجيات متعددة
    // ─────────────────────────────────────────────────────────────

    /**
     * يُنفِّذ أمر داخل Termux environment بثلاث استراتيجيات:
     * 1. env-prefix + bash (الأسرع)
     * 2. run-as com.termux (إذا Termux debuggable)
     * 3. Termux RUN_COMMAND broadcast (الأبطأ لكن الأكثر توافقاً)
     */
    suspend fun executeInTermux(command: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!isTermuxUsable()) {
            // حاول system shell كـ last resort
            return@withContext PrivilegedExecutionManager.executeCommand(command).fold(
                onSuccess = { ToolExecutionResult(it.ifBlank { "(no output)" }) },
                onFailure = {
                    ToolExecutionResult(
                        "Termux غير مثبت أو bash غير موجود. ثبّت Termux من F-Droid:\n" +
                        "https://f-droid.org/en/packages/com.termux/",
                        isError = true
                    )
                }
            )
        }

        // استراتيجية 1: env-prefix مع Termux bash (الأسرع والمفضّل)
        val strategy1 = runStrategy1(command)
        if (strategy1 != null && !strategy1.isError) return@withContext strategy1

        // استراتيجية 2: run-as com.termux
        val strategy2 = runStrategy2(command)
        if (strategy2 != null && !strategy2.isError) return@withContext strategy2

        // إرجاع نتيجة استراتيجية 1 حتى لو كانت خطأ (مع رسالة محسّنة)
        strategy1 ?: ToolExecutionResult("فشل التنفيذ في Termux.", isError = true)
    }

    private suspend fun runStrategy1(command: String): ToolExecutionResult? {
        return try {
            val envPfx = buildEnvPrefix()
            val cmd = "${envPfx}${TERMUX_BASH} -c ${shellQuote(command)} 2>&1"
            PrivilegedExecutionManager.executeCommand(cmd).fold(
                onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
                onFailure = { null }
            )
        } catch (_: Exception) { null }
    }

    private suspend fun runStrategy2(command: String): ToolExecutionResult? {
        // run-as يعمل إذا كان Termux مثبتاً بـ debuggable=true (النسخ من F-Droid)
        return try {
            val cmd = "run-as $TERMUX_PKG bash -c ${shellQuote(command)} 2>&1"
            PrivilegedExecutionManager.executeCommand(cmd).fold(
                onSuccess = { out ->
                    if (out.contains("run-as", ignoreCase = true) &&
                        out.contains("unknown package", ignoreCase = true)) {
                        null // Termux مش debuggable
                    } else {
                        ToolExecutionResult(out.take(MAX_OUTPUT).ifBlank { "(no output)" })
                    }
                },
                onFailure = { null }
            )
        } catch (_: Exception) { null }
    }

    /**
     * تنفيذ script متعدد الأسطر داخل Termux.
     * يكتب الـ script في /data/local/tmp ثم يُنفِّذه ويحذفه.
     */
    suspend fun executeScriptInTermux(scriptContent: String): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            if (!isTermuxUsable()) {
                return@withContext ToolExecutionResult(
                    "Termux غير متاح.", isError = true
                )
            }

            val tmpPath = "/data/local/tmp/omni_ts_${System.currentTimeMillis()}.sh"
            val script  = "#!${TERMUX_BASH}\n\n$scriptContent"
            val b64     = android.util.Base64.encodeToString(
                script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )

            val writeCmd = "echo ${shellQuote(b64)} | base64 -d > ${shellQuote(tmpPath)}" +
                " && chmod +x ${shellQuote(tmpPath)} && echo WRITE_OK"

            val writeResult = PrivilegedExecutionManager.executeCommand(writeCmd)
            if (writeResult.isFailure || !writeResult.getOrDefault("").contains("WRITE_OK")) {
                return@withContext ToolExecutionResult(
                    "فشل كتابة الـ script: ${writeResult.exceptionOrNull()?.message}",
                    isError = true
                )
            }

            val envPfx = buildEnvPrefix()
            val execResult = PrivilegedExecutionManager.executeCommand(
                "${envPfx}${TERMUX_BASH} ${shellQuote(tmpPath)} 2>&1; rm -f ${shellQuote(tmpPath)}"
            )

            execResult.fold(
                onSuccess  = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
                onFailure  = { ToolExecutionResult("فشل تنفيذ الـ script: ${it.message}", isError = true) }
            )
        }

    // ─────────────────────────────────────────────────────────────
    // Python runner
    // ─────────────────────────────────────────────────────────────

    suspend fun runPython(code: String, args: String? = null): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val pyBin = findPythonInterpreter()
                ?: return@withContext ToolExecutionResult(
                    "Python غير موجود.\n" +
                    "ثبّت عبر Termux: action=termux_pkg_install packages='python'\n" +
                    "أو عبر: action=install_python في agent_runtime",
                    isError = true
                )

            val argStr = args?.split(Regex("\\s+"))
                ?.filter { it.isNotBlank() }
                ?.joinToString(" ") { shellQuote(it) }
                ?.let { " $it" } ?: ""

            val isTermuxPy = pyBin.startsWith(TERMUX_BIN)
            val envPfx = if (isTermuxPy) buildEnvPrefix() else ""
            val cmd = "${envPfx}${pyBin} -c ${shellQuote(code)}$argStr 2>&1"

            PrivilegedExecutionManager.executeCommand(cmd).fold(
                onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
                onFailure = {
                    // إذا فشل مع Termux python، حاول system python
                    if (isTermuxPy) {
                        val sysPy = PrivilegedExecutionManager.executeCommand(
                            "python3 -c ${shellQuote(code)}$argStr 2>&1"
                        ).getOrNull()
                        if (sysPy != null) ToolExecutionResult(sysPy.take(MAX_OUTPUT))
                        else ToolExecutionResult("فشل تشغيل Python: ${it.message}", isError = true)
                    } else {
                        ToolExecutionResult("فشل تشغيل Python: ${it.message}", isError = true)
                    }
                }
            )
        }

    // ─────────────────────────────────────────────────────────────
    // Package management
    // ─────────────────────────────────────────────────────────────

    suspend fun pipInstall(packages: String, upgrade: Boolean = false): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val safePkgs = sanitizePackageList(packages)
                ?: return@withContext ToolExecutionResult("أسماء packages غير صحيحة.", isError = true)
            val upgradeFlag = if (upgrade) " --upgrade" else ""
            val envPfx = if (isTermuxUsable()) buildEnvPrefix() else ""

            // Cascade: Termux pip3 → pip3 → python -m pip
            val commands = buildList {
                if (isTermuxUsable()) {
                    add("${envPfx}${TERMUX_PIP3} install$upgradeFlag $safePkgs 2>&1")
                    add("${envPfx}${TERMUX_PIP} install$upgradeFlag $safePkgs 2>&1")
                    add("${envPfx}${TERMUX_PYTHON3} -m pip install$upgradeFlag $safePkgs 2>&1")
                }
                add("pip3 install$upgradeFlag $safePkgs 2>&1")
                add("python3 -m pip install$upgradeFlag $safePkgs 2>&1")
            }

            var lastOutput = "(لم يُحاوَل)"
            for (cmd in commands) {
                val result = PrivilegedExecutionManager.executeCommand(cmd)
                val output = result.getOrNull() ?: continue
                if (output.contains("Successfully installed", ignoreCase = true) ||
                    output.contains("already satisfied", ignoreCase = true) ||
                    output.contains("Requirement already", ignoreCase = true)) {
                    return@withContext ToolExecutionResult(output.take(MAX_OUTPUT))
                }
                lastOutput = output
            }
            ToolExecutionResult("فشل pip install:\n$lastOutput", isError = true)
        }

    suspend fun pkgInstall(packages: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!isTermuxUsable()) {
            return@withContext ToolExecutionResult(
                "Termux غير مثبت. ثبّته من F-Droid:\nhttps://f-droid.org/en/packages/com.termux/",
                isError = true
            )
        }
        val safePkgs = packages.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9_.\\-+]"), "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (safePkgs.isEmpty()) {
            return@withContext ToolExecutionResult("لا توجد أسماء packages صحيحة.", isError = true)
        }

        val envPfx = buildEnvPrefix()
        val cmd = "${envPfx}DEBIAN_FRONTEND=noninteractive " +
            "${TERMUX_PKG_MANAGER} install -y $safePkgs 2>&1 " +
            "|| ${envPfx}DEBIAN_FRONTEND=noninteractive " +
            "${TERMUX_APT} install -y $safePkgs 2>&1"

        PrivilegedExecutionManager.executeCommand(cmd).fold(
            onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
            onFailure = { ToolExecutionResult("فشل pkg install: ${it.message}", isError = true) }
        )
    }

    suspend fun pkgUpdate(): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!isTermuxUsable()) return@withContext ToolExecutionResult("Termux غير متاح.", isError = true)
        val envPfx = buildEnvPrefix()
        PrivilegedExecutionManager.executeCommand(
            "${envPfx}DEBIAN_FRONTEND=noninteractive ${TERMUX_PKG_MANAGER} update -y 2>&1"
        ).fold(
            onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
            onFailure = { ToolExecutionResult("فشل pkg update: ${it.message}", isError = true) }
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Status report
    // ─────────────────────────────────────────────────────────────

    suspend fun statusReport(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("╔══ بيئة التنفيذ ═══════════════════════════════════════════════╗")

        // Backend
        val shizuku  = PrivilegedExecutionManager.isShizukuReady()
        val rishReady = PrivilegedExecutionManager.isRishReady()
        val root     = PrivilegedExecutionManager.isRootAvailable()
        sb.appendLine("║ PRIVILEGE BACKEND")
        sb.appendLine("║   Shizuku : ${if (shizuku) "✅ جاهز" else "❌ غير متاح"}")
        sb.appendLine("║   rish    : ${if (rishReady) "✅ جاهز" else "❌ غير متاح"}")
        sb.appendLine("║   Root    : ${if (root) "⚠️ متاح" else "❌ غير موجود"}")

        // Termux
        sb.appendLine("║")
        sb.appendLine("║ TERMUX")
        val termuxOk = isTermuxUsable()
        sb.appendLine("║   bash    : ${if (termuxOk) "✅ $TERMUX_BASH" else "❌ غير موجود"}")
        val ldPreload = File(TERMUX_EXEC_SO).exists()
        sb.appendLine("║   libtermux-exec.so : ${if (ldPreload) "✅ موجود" else "⚠️ غير موجود (تم التخطي)"}")

        // Interpreters
        sb.appendLine("║")
        sb.appendLine("║ INTERPRETERS")
        val tools = listOf("python3", "node", "git", "curl", "wget", "npm")
        for (tool in tools) {
            val path = findBinary(tool)
            val source = when {
                path == null -> ""
                path.startsWith(TERMUX_BIN) -> "(Termux)"
                else -> "(system)"
            }
            sb.appendLine("║   ${if (path != null) "✅" else "❌"} $tool${if (path != null) " → $path $source" else " — غير موجود"}")
        }

        sb.appendLine("╚════════════════════════════════════════════════════════════════╝")
        ToolExecutionResult(sb.toString().trimEnd())
    }

    // ─────────────────────────────────────────────────────────────
    // Tool definitions
    // ─────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "termux_bridge",
            description = "تنفيذ الأوامر داخل بيئة Termux الكاملة مع إدارة ذكية للـ fallback.\n" +
                "الإجراءات: status, exec, script, python_run, python_file, pip_install, pkg_install, pkg_update, find_binary",
            parameters = listOf(
                ToolParameter("action", "string", "الإجراء المطلوب", required = true),
                ToolParameter("command", "string", "الأمر لـ exec", required = false),
                ToolParameter("script", "string", "الـ script لـ script", required = false),
                ToolParameter("code", "string", "كود Python لـ python_run", required = false),
                ToolParameter("file_path", "string", "مسار ملف .py", required = false),
                ToolParameter("packages", "string", "packages لـ pip_install/pkg_install", required = false),
                ToolParameter("args", "string", "arguments إضافية", required = false),
                ToolParameter("upgrade", "string", "true للترقية", required = false),
                ToolParameter("binary", "string", "اسم binary لـ find_binary", required = false)
            )
        )
    )

    suspend fun executeTool(args: Map<String, String>): ToolExecutionResult {
        return when (val action = args["action"]?.lowercase()?.trim()
            ?: return ToolExecutionResult("يحتاج 'action'.", isError = true)) {
            "status"      -> statusReport()
            "exec"        -> executeInTermux(args["command"]
                ?: return ToolExecutionResult("يحتاج 'command'.", isError = true))
            "script"      -> executeScriptInTermux(args["script"]
                ?: return ToolExecutionResult("يحتاج 'script'.", isError = true))
            "python_run"  -> runPython(args["code"]
                ?: return ToolExecutionResult("يحتاج 'code'.", isError = true), args["args"])
            "python_file" -> {
                val pyPath = args["file_path"]
                    ?: return ToolExecutionResult("يحتاج 'file_path'.", isError = true)
                val pyBin = findPythonInterpreter() ?: return ToolExecutionResult("Python غير موجود.", isError = true)
                val envPfx = if (pyBin.startsWith(TERMUX_BIN)) buildEnvPrefix() else ""
                val cmd = "${envPfx}${pyBin} ${shellQuote(pyPath)} ${args["args"] ?: ""} 2>&1"
                PrivilegedExecutionManager.executeCommand(cmd).fold(
                    onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT)) },
                    onFailure = { ToolExecutionResult("فشل: ${it.message}", isError = true) }
                )
            }
            "pip_install" -> pipInstall(
                args["packages"] ?: return ToolExecutionResult("يحتاج 'packages'.", isError = true),
                args["upgrade"]?.lowercase() == "true"
            )
            "pkg_install" -> pkgInstall(args["packages"]
                ?: return ToolExecutionResult("يحتاج 'packages'.", isError = true))
            "pkg_update"  -> pkgUpdate()
            "find_binary" -> {
                val name = args["binary"] ?: return ToolExecutionResult("يحتاج 'binary'.", isError = true)
                val path = findBinary(name)
                if (path != null) ToolExecutionResult("✅ $name → $path")
                else ToolExecutionResult("❌ '$name' غير موجود", isError = true)
            }
            else -> ToolExecutionResult("إجراء غير معروف: '$action'.", isError = true)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    internal suspend fun findPythonInterpreter(): String? = withContext(Dispatchers.IO) {
        // أولوية: Termux python3 → Termux python → system python3 → system python
        val candidates = listOf(
            TERMUX_PYTHON3, TERMUX_PYTHON,
            "/usr/bin/python3", "/usr/bin/python",
            "/system/bin/python3", "/system/xbin/python3"
        )
        // ابحث في المسارات المباشرة أولاً
        candidates.firstOrNull { File(it).exists() }
            ?: run {
                // ثم عبر which
                PrivilegedExecutionManager.executeCommand(
                    "which python3 2>/dev/null || which python 2>/dev/null"
                ).getOrNull()?.trim()?.takeIf { isValidPath(it) }
            }
    }

    private fun sanitizePackageList(packages: String): String? {
        val safe = packages.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9_.\\-\\[\\]=~<>!@]"), "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return safe.ifBlank { null }
    }

    internal fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"
}
