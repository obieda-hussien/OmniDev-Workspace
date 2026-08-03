package com.omnidev.workspace.data.ipc

import com.omnidev.workspace.core.policy.ConfirmationGate
import com.omnidev.workspace.core.policy.ConfirmationKind
import com.omnidev.workspace.core.policy.OmniAuditLog
import com.omnidev.workspace.core.policy.TierPolicy
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnilink.sdk.AccessDecision
import com.omnilink.sdk.ActionError
import com.omnilink.sdk.ActionOutcome
import com.omnilink.sdk.ActionRequest
import com.omnilink.sdk.CapabilityDescriptor
import com.omnilink.sdk.CapabilityManifest
import com.omnilink.sdk.CallerContext
import com.omnilink.sdk.IExtensionService
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class ExtensionConnectionManagerTest {

    private class StubPolicy(
        override val tier: String,
        override val allowRoot: Boolean = false,
        override val allowShizuku: Boolean = false,
        override val allowAccessibility: Boolean = false,
        override val allowDeepSecurity: Boolean = false,
        override val allowDeviceAdminWipe: Boolean = false,
        override val allowLocalSlm: Boolean = false,
        override val allowSystemIntegration: Boolean = false,
        override val autoApproveConfirmations: Boolean = false
    ) : TierPolicy {
        override fun confirmationGate(uiGate: ConfirmationGate): ConfirmationGate = uiGate
    }

    private val testGate = object : ConfirmationGate {
        var decision = true
        var requestCalled = false
        var lastKind: ConfirmationKind? = null
        var lastPreview: String? = null

        override suspend fun request(kind: ConfirmationKind, preview: String, diffContent: String?): Boolean {
            requestCalled = true
            lastKind = kind
            lastPreview = preview
            return decision
        }
    }

    @Before
    fun setUp() {
        OmniAuditLog.clearForTest()
        testGate.decision = true
        testGate.requestCalled = false
        testGate.lastKind = null
        testGate.lastPreview = null
        ExtensionConnectionManager.handles.clear()
        ExtensionConnectionManager.confirmationGate = testGate
        ExtensionConnectionManager.getMyUid = { 10001 }
        ExtensionConnectionManager.createResultCallback = { resume ->
            Proxy.newProxyInstance(
                com.omnilink.sdk.IOmniResultCallback::class.java.classLoader,
                arrayOf(com.omnilink.sdk.IOmniResultCallback::class.java)
            ) { _, method, args ->
                if (method.name == "onResult") {
                    resume(args[0] as String)
                }
                null
            } as com.omnilink.sdk.IOmniResultCallback
        }
    }

    @After
    fun tearDown() {
        OmniAuditLog.clearForTest()
        ExtensionConnectionManager.handles.clear()
        ExtensionConnectionManager.confirmationGate = null
    }

    private fun createMockBinder(
        onExecuteAction: (Int, String) -> String = { _, _ -> "{}" },
        onExecuteActionAsync: (Int, String, com.omnilink.sdk.IOmniResultCallback) -> Unit = { _, _, cb -> cb.onResult(Json.encodeToString(ActionOutcome.serializer(), ActionOutcome.Success(JsonPrimitive("binder_success")))) }
    ): IExtensionService {
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            when (method.name) {
                "executeAction" -> onExecuteAction(args[0] as Int, args[1] as String)
                "executeActionAsync" -> {
                    onExecuteActionAsync(args[0] as Int, args[1] as String, args[2] as com.omnilink.sdk.IOmniResultCallback)
                    null
                }
                "asBinder" -> null
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            IExtensionService::class.java.classLoader,
            arrayOf(IExtensionService::class.java),
            handler
        ) as IExtensionService
    }

    @Test
    fun testWorkspaceAccessController_LiteTier_Denied() {
        // Force Lite Tier
        TierPolicyHolder.install(StubPolicy(tier = "LITE"))

        val controller = WorkspaceAccessController()
        val caller = CallerContext(10001, "com.example.ext")
        val request = ActionRequest("moveToTrash", JsonPrimitive("test"))

        val decision = controller.decide(caller, request)
        assertEquals(AccessDecision.DENY, decision)
    }

    @Test
    fun testWorkspaceAccessController_ProTier_MoveToTrash_NoCache_Allow() {
        // Force Pro Tier
        TierPolicyHolder.install(StubPolicy(tier = "PRO"))

        val controller = WorkspaceAccessController()
        val caller = CallerContext(10001, "com.example.ext")
        val request = ActionRequest("someOtherAction", JsonPrimitive("test"))

        val decision = controller.decide(caller, request)
        assertEquals(AccessDecision.ALLOW, decision)
    }

    @Test
    fun testWorkspaceAccessController_ProTier_MoveToTrash_WithCache_RequiresConfirmation() {
        // Force Pro Tier
        TierPolicyHolder.install(StubPolicy(tier = "PRO"))

        // Add dummy handle with manifest requiring confirmation for "moveToTrash"
        val descriptor = CapabilityDescriptor("moveToTrash", destructive = true, requiresConfirmation = true)
        val manifest = CapabilityManifest(
            protocolVersion = 1,
            sdkVersion = "1.0.0",
            minSupportedVersion = 1,
            maxSupportedVersion = 1,
            capabilities = listOf(descriptor),
            supportsTicks = false,
            preferredTickIntervalSeconds = 0
        )
        val handle = ExtensionConnectionManager.ExtensionHandle(
            packageName = "com.example.ext",
            serviceClassName = "com.example.ext.MyService",
            manifest = manifest
        )
        ExtensionConnectionManager.handles[handle.id] = handle

        val controller = WorkspaceAccessController()
        val caller = CallerContext(10001, "com.example.ext")
        val request = ActionRequest("moveToTrash", JsonPrimitive("test"))

        val decision = controller.decide(caller, request)
        assertEquals(AccessDecision.REQUIRES_CONFIRMATION, decision)
    }

    @Test
    fun testBug1Regression_WrongPackageName_ReturnsAllow() {
        // Force Pro Tier
        TierPolicyHolder.install(StubPolicy(tier = "PRO"))

        // Add dummy handle with manifest requiring confirmation for "moveToTrash"
        val descriptor = CapabilityDescriptor("moveToTrash", destructive = true, requiresConfirmation = true)
        val manifest = CapabilityManifest(
            protocolVersion = 1,
            sdkVersion = "1.0.0",
            minSupportedVersion = 1,
            maxSupportedVersion = 1,
            capabilities = listOf(descriptor),
            supportsTicks = false,
            preferredTickIntervalSeconds = 0
        )
        val handle = ExtensionConnectionManager.ExtensionHandle(
            packageName = "com.example.ext",
            serviceClassName = "com.example.ext.MyService",
            manifest = manifest
        )
        ExtensionConnectionManager.handles[handle.id] = handle

        val controller = WorkspaceAccessController()
        // BUG #1 check: Construct CallerContext with Workspace's own package name instead of target extension package.
        // It should return ALLOW because requiresConfirmation inside decide() fails due to wrong package matching!
        val caller = CallerContext(10001, "com.omnidev.workspace")
        val request = ActionRequest("moveToTrash", JsonPrimitive("test"))

        val decision = controller.decide(caller, request)
        assertEquals(AccessDecision.ALLOW, decision)
    }

    @Test
    fun testWorkspaceAuditLogger_LogsSuccessFailureAndRequiresConfirmation() {
        TierPolicyHolder.install(StubPolicy(tier = "PRO"))
        val logger = WorkspaceAuditLogger()

        val caller = CallerContext(10001, "com.example.ext")
        val request = ActionRequest("moveToTrash", JsonPrimitive("test"))

        // Log starting/success
        logger.log(caller, request, ActionOutcome.Success(JsonPrimitive("ok")))
        var logs = OmniAuditLog.snapshot()
        assertEquals(1, logs.size)
        assertTrue(logs[0].preview.contains("SUCCESS"))
        assertTrue(logs[0].preview.contains("com.example.ext"))

        // Log failure
        logger.log(caller, request, ActionOutcome.Failure(ActionError("error_code", "failed miserably")))
        logs = OmniAuditLog.snapshot()
        assertEquals(2, logs.size)
        assertTrue(logs[1].preview.contains("FAILURE: [error_code] failed miserably"))

        // BUG #4 check: Log RequiresConfirmation outcome
        logger.log(caller, request, ActionOutcome.RequiresConfirmation("Please confirm action execution"))
        logs = OmniAuditLog.snapshot()
        assertEquals(3, logs.size)
        assertTrue("Log should contain REQUIRES_CONFIRMATION message", logs[2].preview.contains("REQUIRES_CONFIRMATION: Please confirm action execution"))
    }

    @Test
    fun testEndToEnd_ExecuteAction_WithConfirmationApproval() = runTest {
        TierPolicyHolder.install(StubPolicy(tier = "PRO"))

        var binderExecutionCount = 0
        val mockBinder = createMockBinder { _, _, cb ->
            binderExecutionCount++
            val successOutcome = ActionOutcome.Success(JsonPrimitive("binder_executed"))
            cb.onResult(Json.encodeToString(ActionOutcome.serializer(), successOutcome))
        }

        val descriptor = CapabilityDescriptor("moveToTrash", destructive = true, requiresConfirmation = true)
        val manifest = CapabilityManifest(
            protocolVersion = 1,
            sdkVersion = "1.0.0",
            minSupportedVersion = 1,
            maxSupportedVersion = 1,
            capabilities = listOf(descriptor),
            supportsTicks = false,
            preferredTickIntervalSeconds = 0
        )
        val handle = ExtensionConnectionManager.ExtensionHandle(
            packageName = "com.example.ext",
            serviceClassName = "com.example.ext.MyService",
            binder = mockBinder,
            manifest = manifest
        )
        ExtensionConnectionManager.handles[handle.id] = handle

        testGate.decision = true // Approve confirmation

        val resultJson = ExtensionConnectionManager.executeAction(handle.id, "moveToTrash", "{}")

        assertTrue("Confirmation gate should be invoked", testGate.requestCalled)
        assertEquals("Binder should be executed since confirmation was approved", 1, binderExecutionCount)

        val outcome = Json.decodeFromString<ActionOutcome>(ActionOutcome.serializer(), resultJson)
        assertTrue(outcome is ActionOutcome.Success)
        assertEquals(JsonPrimitive("binder_executed"), (outcome as ActionOutcome.Success).data)

        // Verify OmniAuditLog contains REQUIRES_CONFIRMATION and SUCCESS entries
        val logs = OmniAuditLog.snapshot()
        assertTrue(logs.any { it.preview.contains("REQUIRES_CONFIRMATION: Confirmation required for action moveToTrash") })
        assertTrue(logs.any { it.preview.contains("SUCCESS") })
    }

    @Test
    fun testEndToEnd_ExecuteAction_WithConfirmationDenial() = runTest {
        TierPolicyHolder.install(StubPolicy(tier = "PRO"))

        var binderExecutionCount = 0
        val mockBinder = createMockBinder { _, _, cb ->
            binderExecutionCount++
            val successOutcome = ActionOutcome.Success(JsonPrimitive("binder_executed"))
            cb.onResult(Json.encodeToString(ActionOutcome.serializer(), successOutcome))
        }

        val descriptor = CapabilityDescriptor("moveToTrash", destructive = true, requiresConfirmation = true)
        val manifest = CapabilityManifest(
            protocolVersion = 1,
            sdkVersion = "1.0.0",
            minSupportedVersion = 1,
            maxSupportedVersion = 1,
            capabilities = listOf(descriptor),
            supportsTicks = false,
            preferredTickIntervalSeconds = 0
        )
        val handle = ExtensionConnectionManager.ExtensionHandle(
            packageName = "com.example.ext",
            serviceClassName = "com.example.ext.MyService",
            binder = mockBinder,
            manifest = manifest
        )
        ExtensionConnectionManager.handles[handle.id] = handle

        testGate.decision = false // Deny confirmation

        val resultJson = ExtensionConnectionManager.executeAction(handle.id, "moveToTrash", "{}")

        assertTrue("Confirmation gate should be invoked", testGate.requestCalled)
        assertEquals("Binder should NOT be executed since confirmation was denied", 0, binderExecutionCount)

        val outcome = Json.decodeFromString<ActionOutcome>(ActionOutcome.serializer(), resultJson)
        assertTrue(outcome is ActionOutcome.Failure)
        assertEquals("confirmation_denied", (outcome as ActionOutcome.Failure).error.code)

        // Verify OmniAuditLog contains REQUIRES_CONFIRMATION and FAILURE entries
        val logs = OmniAuditLog.snapshot()
        assertTrue(logs.any { it.preview.contains("REQUIRES_CONFIRMATION: Confirmation required for action moveToTrash") })
        assertTrue(logs.any { it.preview.contains("FAILURE: [confirmation_denied]") })
    }

    @Test
    fun testExtensionToolDefinitions_ConvertsCapabilities() {
        val descriptor1 = CapabilityDescriptor("moveToTrash", destructive = true, requiresConfirmation = true)
        val descriptor2 = CapabilityDescriptor("emptyTrash", destructive = true, requiresConfirmation = false)
        val manifest = CapabilityManifest(
            protocolVersion = 1,
            sdkVersion = "1.0.0",
            minSupportedVersion = 1,
            maxSupportedVersion = 1,
            capabilities = listOf(descriptor1, descriptor2),
            supportsTicks = false,
            preferredTickIntervalSeconds = 0
        )
        val handle = ExtensionConnectionManager.ExtensionHandle(
            packageName = "com.example.my_extension",
            serviceClassName = "com.example.my_extension.Service",
            manifest = manifest
        )
        ExtensionConnectionManager.handles[handle.id] = handle

        val tools = ExtensionConnectionManager.getExtensionToolDefinitions()
        assertEquals(2, tools.size)

        val tool1 = tools.firstOrNull { it.name == "ext_com_example_my_extension_moveToTrash" }
        assertNotNull(tool1)
        assertTrue(tool1!!.description.contains("moveToTrash"))
        assertEquals(1, tool1.parameters.size)
        assertEquals("payload", tool1.parameters[0].name)

        val tool2 = tools.firstOrNull { it.name == "ext_com_example_my_extension_emptyTrash" }
        assertNotNull(tool2)
        assertTrue(tool2!!.description.contains("emptyTrash"))
    }

    @Test
    fun testExecute_E2E_Success() = runTest {
        TierPolicyHolder.install(StubPolicy(tier = "PRO"))

        val mockBinder = createMockBinder { _, _, cb ->
            val successOutcome = ActionOutcome.Success(JsonPrimitive("execution_ok"))
            cb.onResult(Json.encodeToString(ActionOutcome.serializer(), successOutcome))
        }

        val descriptor = CapabilityDescriptor("cleanCache", destructive = false, requiresConfirmation = false)
        val manifest = CapabilityManifest(
            protocolVersion = 1,
            sdkVersion = "1.0.0",
            minSupportedVersion = 1,
            maxSupportedVersion = 1,
            capabilities = listOf(descriptor),
            supportsTicks = false,
            preferredTickIntervalSeconds = 0
        )
        val handle = ExtensionConnectionManager.ExtensionHandle(
            packageName = "com.example.tools",
            serviceClassName = "com.example.tools.Service",
            binder = mockBinder,
            manifest = manifest
        )
        ExtensionConnectionManager.handles[handle.id] = handle

        val resultJson = ExtensionConnectionManager.execute(
            appName = "com_example_tools",
            actionName = "cleanCache",
            jsonPayload = "{\"param\":\"value\"}"
        )

        val outcome = Json.decodeFromString<ActionOutcome>(ActionOutcome.serializer(), resultJson)
        assertTrue(outcome is ActionOutcome.Success)
        assertEquals(JsonPrimitive("execution_ok"), (outcome as ActionOutcome.Success).data)
    }

    @Test
    fun testToolNameParsing_FullRoundTrip_MultiSegment() {
        val descriptor = CapabilityDescriptor("moveToTrash", destructive = true, requiresConfirmation = true)
        val manifest = CapabilityManifest(
            protocolVersion = 1,
            sdkVersion = "1.0.0",
            minSupportedVersion = 1,
            maxSupportedVersion = 1,
            capabilities = listOf(descriptor),
            supportsTicks = false,
            preferredTickIntervalSeconds = 0
        )
        val handle = ExtensionConnectionManager.ExtensionHandle(
            packageName = "com.example.notelink",
            serviceClassName = "com.example.notelink.Service",
            manifest = manifest
        )
        ExtensionConnectionManager.handles[handle.id] = handle

        // Generate the tool definitions
        val tools = ExtensionConnectionManager.getExtensionToolDefinitions()
        assertEquals(1, tools.size)

        val generatedName = tools[0].name
        assertEquals("ext_com_example_notelink_moveToTrash", generatedName)

        // Parse it back using the same parsing logic
        val parsed = ExtensionConnectionManager.parseToolName(generatedName)
        assertNotNull(parsed)
        assertEquals("com_example_notelink", parsed!!.first)
        assertEquals("moveToTrash", parsed.second)
    }

    @Test
    fun testWorkspaceAccessController_NormTier_Denied() {
        // Force Norm Tier
        TierPolicyHolder.install(StubPolicy(tier = "NORM"))

        val controller = WorkspaceAccessController()
        val caller = CallerContext(10001, "com.example.ext")
        val request = ActionRequest("moveToTrash", JsonPrimitive("test"))

        val decision = controller.decide(caller, request)
        assertEquals(AccessDecision.DENY, decision)
    }

    @Test
    fun testExecuteAction_LiteTier_AuditLogDenial() = runTest {
        // Force Lite Tier
        TierPolicyHolder.install(StubPolicy(tier = "LITE"))

        // Add dummy handle
        val handle = ExtensionConnectionManager.ExtensionHandle(
            packageName = "com.example.ext",
            serviceClassName = "com.example.ext.MyService",
            binder = createMockBinder()
        )
        ExtensionConnectionManager.handles[handle.id] = handle

        // Execute action — should be denied by pre-flight checks
        val resultJson = ExtensionConnectionManager.executeAction(handle.id, "moveToTrash", "{}")

        val outcome = Json.decodeFromString<ActionOutcome>(ActionOutcome.serializer(), resultJson)
        assertTrue(outcome is ActionOutcome.Failure)
        assertEquals("denied", (outcome as ActionOutcome.Failure).error.code)

        // Verify OmniAuditLog has a denial record
        val logs = OmniAuditLog.snapshot()
        assertTrue(logs.any { it.preview.contains("FAILURE: [denied] Execution denied by AccessController") })
    }

    @Test
    fun testRefreshDiscoveredExtensions_LiteTier_BlockedAndAuditLogged() {
        // Force Lite Tier
        TierPolicyHolder.install(StubPolicy(tier = "LITE"))

        // Clear handles map
        ExtensionConnectionManager.handles.clear()

        // Call refreshDiscoveredExtensions
        ExtensionConnectionManager.refreshDiscoveredExtensions()

        // Assert that handles map remains empty
        assertTrue("Handles map must be empty in Lite tier", ExtensionConnectionManager.handles.isEmpty())

        // Assert that OmniAuditLog has recorded the discovery denial
        val logs = OmniAuditLog.snapshot()
        assertEquals(1, logs.size)
        assertTrue(logs[0].preview.contains("Extension discovery blocked: extensions are not supported on tier LITE"))
        assertEquals("LITE", logs[0].tier)
    }

    // Helper to build functional mock SharedPreferences backed by an in-memory map
    private fun createMockSharedPreferences(testPrefsMap: MutableMap<String, Any>): android.content.SharedPreferences {
        return object : android.content.SharedPreferences {
            override fun getAll(): Map<String, *> = testPrefsMap
            override fun getString(key: String, defValue: String?): String? = testPrefsMap[key] as? String ?: defValue
            override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = testPrefsMap[key] as? Set<String> ?: defValues
            override fun getInt(key: String, defValue: Int): Int = testPrefsMap[key] as? Int ?: defValue
            override fun getLong(key: String, defValue: Long): Long = testPrefsMap[key] as? Long ?: defValue
            override fun getFloat(key: String, defValue: Float): Float = testPrefsMap[key] as? Float ?: defValue
            override fun getBoolean(key: String, defValue: Boolean): Boolean = testPrefsMap[key] as? Boolean ?: defValue
            override fun contains(key: String): Boolean = testPrefsMap.containsKey(key)
            override fun edit(): android.content.SharedPreferences.Editor {
                return object : android.content.SharedPreferences.Editor {
                    val putMap = mutableMapOf<String, Any>()
                    override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor { value?.let { putMap[key] = it }; return this }
                    override fun putStringSet(key: String, values: Set<String>?): android.content.SharedPreferences.Editor { values?.let { putMap[key] = it }; return this }
                    override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor { putMap[key] = value; return this }
                    override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor { putMap[key] = value; return this }
                    override fun putFloat(key: String, value: Float): android.content.SharedPreferences.Editor { putMap[key] = value; return this }
                    override fun putBoolean(key: String, value: Boolean): android.content.SharedPreferences.Editor { putMap[key] = value; return this }
                    override fun remove(key: String): android.content.SharedPreferences.Editor { putMap.remove(key); return this }
                    override fun clear(): android.content.SharedPreferences.Editor { putMap.clear(); return this }
                    override fun commit(): Boolean { testPrefsMap.putAll(putMap); return true }
                    override fun apply() { testPrefsMap.putAll(putMap) }
                }
            }
            override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
    }

    @Test
    fun testExtensionTickWorker_ConcurrentExecution_WithOneHungExtension() = runBlocking {
        TierPolicyHolder.install(StubPolicy(tier = "PRO"))

        var firstExecuted = 0
        var secondExecuted = 0

        // Mock Binder 1: completes instantly
        val mockBinder1 = createMockBinder { _, _, cb ->
            firstExecuted++
            val successOutcome = ActionOutcome.Success(JsonPrimitive("success_1"))
            cb.onResult(Json.encodeToString(ActionOutcome.serializer(), successOutcome))
        }

        // Mock Binder 2: hangs indefinitely
        val mockBinder2 = createMockBinder { _, _, cb ->
            secondExecuted++
            // Simulates hang — never calls cb.onResult
        }

        val descriptor = CapabilityDescriptor("_tick", destructive = false, requiresConfirmation = false)
        val manifest = CapabilityManifest(
            protocolVersion = 1,
            sdkVersion = "1.0.0",
            minSupportedVersion = 1,
            maxSupportedVersion = 1,
            capabilities = listOf(descriptor),
            supportsTicks = true,
            preferredTickIntervalSeconds = 1 // Always due
        )

        val handle1 = ExtensionConnectionManager.ExtensionHandle(
            packageName = "com.example.first",
            serviceClassName = "com.example.first.Service",
            binder = mockBinder1,
            manifest = manifest
        )
        val handle2 = ExtensionConnectionManager.ExtensionHandle(
            packageName = "com.example.second",
            serviceClassName = "com.example.second.Service",
            binder = mockBinder2,
            manifest = manifest
        )

        ExtensionConnectionManager.handles[handle1.id] = handle1
        ExtensionConnectionManager.handles[handle2.id] = handle2

        ExtensionTickWorker.lastTickTimestamps.clear()
        ExtensionTickWorker.tickTimeoutMs = 100L // 100ms timeout to keep test fast!

        val testPrefsMap = mutableMapOf<String, Any>()
        val mockPrefs = createMockSharedPreferences(testPrefsMap)

        val context = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = mockPrefs
        }

        // Use TestListenableWorkerBuilder to construct our worker in a completely robust, non-fragile way!
        val worker = androidx.work.testing.TestListenableWorkerBuilder.from(context, ExtensionTickWorker::class.java).build()
        val result = worker.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        assertEquals("First extension should have completed tick execution", 1, firstExecuted)
        assertEquals("Second extension should have attempted tick execution", 1, secondExecuted)

        // Assert that prefs is updated for handle1 but NOT for handle2 (due to hang/timeout)
        assertNotNull("First extension last successful tick timestamp should be recorded in SharedPreferences", testPrefsMap["tick_${handle1.id}"])
        assertNull("Second extension last successful tick timestamp should NOT be recorded in SharedPreferences", testPrefsMap["tick_${handle2.id}"])

        // Verify OmniAuditLog did NOT log a success entry for first extension (bypassed to avoid flooding!)
        val logs = OmniAuditLog.snapshot()
        assertFalse("Successful ticks must NOT flood OmniAuditLog", logs.any { it.preview.contains("SUCCESS") })
    }

    @Test
    fun testExtensionTickWorker_PersistedTimestamps_SurvivesProcessDeath() = runBlocking {
        TierPolicyHolder.install(StubPolicy(tier = "PRO"))

        var tickExecuted = 0
        val mockBinder = createMockBinder { _, _, cb ->
            tickExecuted++
            val successOutcome = ActionOutcome.Success(JsonPrimitive("success_ok"))
            cb.onResult(Json.encodeToString(ActionOutcome.serializer(), successOutcome))
        }

        val descriptor = CapabilityDescriptor("_tick", destructive = false, requiresConfirmation = false)
        val manifest = CapabilityManifest(
            protocolVersion = 1,
            sdkVersion = "1.0.0",
            minSupportedVersion = 1,
            maxSupportedVersion = 1,
            capabilities = listOf(descriptor),
            supportsTicks = true,
            preferredTickIntervalSeconds = 3600 // 1 hour interval
        )

        val handle = ExtensionConnectionManager.ExtensionHandle(
            packageName = "com.example.hourly",
            serviceClassName = "com.example.hourly.Service",
            binder = mockBinder,
            manifest = manifest
        )
        ExtensionConnectionManager.handles[handle.id] = handle

        // Simulate process death and recent successful tick.
        // We initialize a testPrefsMap with a timestamp from 10 seconds ago (so it is not due yet)
        val testPrefsMap = mutableMapOf<String, Any>()
        val currentTime = System.currentTimeMillis()
        testPrefsMap["tick_${handle.id}"] = currentTime - 10_000L // Ticked 10s ago

        val mockPrefs = createMockSharedPreferences(testPrefsMap)
        val context = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = mockPrefs
        }

        // Instantiate a completely new worker to simulate clean start after process death
        val worker = androidx.work.testing.TestListenableWorkerBuilder.from(context, ExtensionTickWorker::class.java).build()
        val result = worker.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        assertEquals("Hourly extension should NOT run tick again because its 3600s interval has not elapsed since recent persisted tick", 0, tickExecuted)

        // Now simulate that 1 hour has indeed elapsed (e.g. last tick was 2 hours ago)
        testPrefsMap["tick_${handle.id}"] = currentTime - 7200_000L // Ticked 2h ago

        val worker2 = androidx.work.testing.TestListenableWorkerBuilder.from(context, ExtensionTickWorker::class.java).build()
        val result2 = worker2.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result2)
        assertEquals("Hourly extension should run tick now because its interval has elapsed", 1, tickExecuted)
    }

}
