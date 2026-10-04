package com.omnidev.workspace.ui.assistant

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.data.tools.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Retains an in-progress setup across rotation, without retaining an Activity. */
internal class DeviceAccessViewModel(application: Application) : AndroidViewModel(application) {
    enum class RequestKind { SHIZUKU, RUNTIME, SETTINGS }
    data class Pending(val id: Long, val step: DeviceAccessSetupPlan.Step, val kind: RequestKind, val dispatched: Boolean = false)
    data class State(
        val access: List<DeviceAccessSetupPlan.Access> = emptyList(),
        val backends: Map<String, String> = emptyMap(),
        val loaded: Boolean = false,
        val busy: Boolean = false,
        val completed: Int = 0,
        val total: Int = 0,
        val progress: String = "",
        val pending: Pending? = null,
        val message: String? = null,
        val failures: List<String> = emptyList()
    )
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val context get() = getApplication<Application>()
    private var job: Job? = null
    private var refreshJob: Job? = null
    private var requestId = 0L
    private var backend = "auto"
    private var fullSetup = false
    private val attempted = mutableSetOf<String>()

    init { refresh() }

    private suspend fun snapshot() {
        val (access, backends) = withContext(Dispatchers.IO) {
            PermissionManagerTool.accessSnapshot(context) to listOf("shizuku", "rish", "root", "system", "device_owner", "profile_owner")
                .associateWith { DeviceAccessCatalog.status(context, it) }
        }
        mutableState.value = mutableState.value.copy(access = access, backends = backends, loaded = true)
    }

    fun refresh() {
        if (mutableState.value.busy && mutableState.value.pending == null) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { snapshot() }
    }

    fun start(selectedBackend: String) {
        if (mutableState.value.busy || selectedBackend !in setOf("auto", "root", "android")) return
        refreshJob?.cancel()
        backend = selectedBackend
        fullSetup = true
        attempted.clear()
        mutableState.value = mutableState.value.copy(busy = true, pending = null, failures = emptyList(), message = null,
            completed = 0, total = 0, progress = "Checking access")
        runSetup {
            snapshot()
            if (backend == "auto" && TierPolicyHolder.current.allowShizuku && !PermissionManagerTool.privilegedBackendReady()) {
                if (ShizukuCommandTool.isAvailable()) {
                    pending(DeviceAccessSetupPlan.Step("shizuku", "Authorize Shizuku"), RequestKind.SHIZUKU)
                    return@runSetup
                }
                mutableState.value = mutableState.value.copy(message = "Start Shizuku for automatic grants. Continuing with Android approvals.",
                    failures = listOf("Shizuku was not running; this session used Android approvals."))
                backend = "android"
            }
            if (backend == "root") {
                val result = PermissionManagerTool.requestPermission(context, "root", "root")
                if (result.isError) {
                    mutableState.value = mutableState.value.copy(message = result.output, failures = listOf(result.output))
                    backend = "android"
                }
            }
            automate()
        }
    }

    private fun runSetup(action: suspend () -> Unit) {
        job = viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.value = mutableState.value.copy(busy = false, pending = null,
                    message = "Setup paused: ${error.message.orEmpty().take(250)}. You can retry.")
            }
        }
    }

    private suspend fun automate() {
        val failures = PermissionManagerTool.grantAllSupportedAccess(context, backend) { done, total, title ->
            // This callback runs on IO. StateFlow updates do not touch the Compose snapshot system.
            mutableState.update { it.copy(completed = done, total = total, progress = title.substringAfterLast('.').replace('_', ' ')) }
        }
        mutableState.value = mutableState.value.copy(failures = mutableState.value.failures + failures)
        advance()
    }

    private suspend fun advance() {
        snapshot()
        val next = if (fullSetup) DeviceAccessSetupPlan.nextStep(mutableState.value.access, attempted, Build.VERSION.SDK_INT) else null
        if (next == null) {
            val access = mutableState.value.access
            val remaining = access.count { !it.granted && it.actionable && !it.declaration }
            val runtime = access.count { !it.granted && it.route == DeviceAccessSetupPlan.Route.RUNTIME }
            val development = access.count { !it.granted && it.route == DeviceAccessSetupPlan.Route.DEVELOPMENT }
            mutableState.value = mutableState.value.copy(busy = false, pending = null, progress = "Setup finished",
                message = "Setup finished. ${access.count { it.declaration && it.granted }}/${access.count { it.declaration }} declared permissions available. " +
                    if (remaining + runtime + development > 0) "$remaining special-access steps, $runtime app permissions and $development development grants remain. Review them below."
                    else "All supported app permissions and special-access steps are available.")
        } else pending(next, if (next.permissions.isEmpty() || PermissionRequestPlan.usesAppDetails(next.key, Build.VERSION.SDK_INT)) RequestKind.SETTINGS else RequestKind.RUNTIME)
    }

    private fun pending(step: DeviceAccessSetupPlan.Step, kind: RequestKind) {
        mutableState.value = mutableState.value.copy(pending = Pending(++requestId, step, kind), progress = step.title)
    }

    fun markDispatched(id: Long): Pending? {
        val pending = mutableState.value.pending?.takeIf { it.id == id && !it.dispatched } ?: return null
        mutableState.value = mutableState.value.copy(pending = pending.copy(dispatched = true))
        return pending
    }

    fun externalResult(id: Long, error: String? = null) {
        val pending = mutableState.value.pending?.takeIf { it.id == id && it.dispatched } ?: return
        refreshJob?.cancel()
        attempted += pending.step.key
        attempted += pending.step.permissions.filterNot { it in PermissionRequestPlan.staged }
        mutableState.value = mutableState.value.copy(pending = null, message = error ?: mutableState.value.message)
        runSetup {
            if (pending.kind == RequestKind.SHIZUKU) {
                // A denied authorization never loops and never escalates to root.
                if (!PermissionManagerTool.privilegedBackendReady()) {
                    backend = "android"
                    mutableState.value = mutableState.value.copy(failures = mutableState.value.failures + "Shizuku authorization unavailable or skipped; continuing with Android approvals.")
                }
                automate()
            } else advance()
        }
    }

    fun skip() {
        val pending = mutableState.value.pending ?: return
        mutableState.value = mutableState.value.copy(pending = pending.copy(dispatched = true))
        externalResult(pending.id)
    }

    fun cancel() {
        job?.cancel()
        fullSetup = false
        mutableState.value = mutableState.value.copy(busy = false, pending = null, message = "Setup stopped. Access already granted is kept.")
        refresh()
    }

    fun requestSingle(key: String, selectedBackend: String) {
        if (mutableState.value.busy) return
        val access = mutableState.value.access.firstOrNull { it.key == key } ?: return
        if (!access.actionable || access.granted) return
        refreshJob?.cancel()
        fullSetup = false
        backend = selectedBackend
        mutableState.value = mutableState.value.copy(busy = true, message = null)
        runSetup {
            val prerequisites = PermissionRequestPlan.prerequisites(key)
            if (prerequisites.isNotEmpty() && mutableState.value.access.none { it.key in prerequisites && it.granted }) {
                mutableState.value = mutableState.value.copy(busy = false, message = "Grant foreground access before ${access.title}.")
            } else when (access.route) {
                DeviceAccessSetupPlan.Route.RUNTIME -> {
                    val batch = if (key in PermissionRequestPlan.staged) listOf(key) else PermissionRequestPlan.foregroundBatch(listOf(key), Build.VERSION.SDK_INT)
                    val declared = PermissionManagerTool.declaredPermissions(context)
                    pending(DeviceAccessSetupPlan.Step(key, access.title, batch.filter { it in declared }),
                        if (PermissionRequestPlan.usesAppDetails(key, Build.VERSION.SDK_INT)) RequestKind.SETTINGS else RequestKind.RUNTIME)
                }
                DeviceAccessSetupPlan.Route.SPECIAL -> {
                    val specialKey = if (access.declaration) DeviceAccessCatalog.setupKey(key) ?: key else key
                    pending(DeviceAccessSetupPlan.Step(specialKey, access.title), RequestKind.SETTINGS)
                }
                else -> {
                    if (selectedBackend == "android") {
                        mutableState.value = mutableState.value.copy(busy = false,
                            message = "This permission requires a privileged backend. Select Shizuku, System or Root if available in this edition.")
                        return@runSetup
                    }
                    val result = PermissionManagerTool.requestPermission(context, key, if (selectedBackend == "root") "root" else "auto")
                    snapshot()
                    mutableState.value = mutableState.value.copy(busy = false, message = result.output)
                }
            }
        }
    }
}
