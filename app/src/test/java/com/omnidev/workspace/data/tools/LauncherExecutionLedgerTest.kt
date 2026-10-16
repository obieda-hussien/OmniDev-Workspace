package com.omnidev.workspace.data.tools

import com.omnidev.workspace.domain.engine.LauncherExecutionLedger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LauncherExecutionLedgerTest {
    private fun args(action: String, dry: Boolean = false) = mapOf("action" to "execute_capability", "action_name" to action,
        "json_payload" to "{\"expectedRevision\":\"old\"}", "dry_run" to dry.toString())
    private fun outcome(data: JSONObject) = JSONObject().put("ok", true).put("result_json", JSONObject()
        .put("type", "com.omnilink.sdk.ActionOutcome.Success").put("data", data).toString()).toString()
    @Test fun inspectionAndPlanOnlyDoNotRequireMutations() {
        assertFalse(LauncherExecutionLedger("Inspect launcher capabilities").requiresAction)
        assertFalse(LauncherExecutionLedger("Organize launcher, plan only, do not execute").requiresAction)
    }
    @Test fun arabicFollowupRetainsOrganizationIntent() {
        assertTrue(LauncherExecutionLedger("رتب التطبيقات والمجلدات على الشاشة\nابدء يالا").requiresAction)
        assertFalse(LauncherExecutionLedger("رتب التطبيقات بس متنفذش").requiresAction)
    }
    @Test fun readsAndShellFilesCannotCompleteOrganization() {
        val ledger = LauncherExecutionLedger("Organize launcher home screen")
        ledger.observe("omni_link", args("launcher.health"), outcome(JSONObject().put("ready", true)), false)
        ledger.observe("omni_link", args("launcher.get_layout"), outcome(JSONObject().put("revision", "old")), false)
        ledger.observe("run_terminal", emptyMap(), "exit=0", false)
        assertNotNull(ledger.completionFeedback())
        assertNotNull(ledger.checkTool("run_terminal", mapOf("command" to "mkdir Organizer")))
    }
    @Test fun dryRunsAndFailuresAreNotMutations() {
        val ledger = LauncherExecutionLedger("Organize launcher home screen")
        ledger.observe("omni_link", args("launcher.move_items", true), outcome(JSONObject()), false)
        assertNotNull(ledger.completionFeedback())
        ledger.observe("omni_link", args("launcher.move_items"), "{\"ok\":false,\"code\":\"stale_layout\"}", true)
        assertNotNull(ledger.completionFeedback())
    }
    @Test fun scheduledMutationNeedsFreshLayout() {
        val ledger = LauncherExecutionLedger("Organize launcher home screen")
        ledger.observe("omni_link", args("launcher.move_items"), outcome(JSONObject().put("reloadScheduled", true)), false)
        assertNotNull(ledger.completionFeedback())
        ledger.observe("omni_link", args("launcher.get_layout"), outcome(JSONObject().put("revision", "old")), false)
        assertNotNull(ledger.completionFeedback())
        ledger.observe("omni_link", args("launcher.get_layout"), outcome(JSONObject().put("revision", "new")), false)
        assertNull(ledger.completionFeedback())
    }
    @Test fun drawerFolderDoesNotSubstituteForHomeFolder() {
        val ledger = LauncherExecutionLedger("Create folders on launcher home screen")
        ledger.observe("omni_link", args("launcher.create_folder"), outcome(JSONObject().put("folderId", 5)), false)
        assertNotNull(ledger.completionFeedback())
    }
    @Test fun recoveryIsBoundedAndExplicitExportCanWrite() {
        val ledger = LauncherExecutionLedger("Organize launcher home screen")
        repeat(3) { assertNotNull(ledger.completionFeedback()) }
        assertTrue(ledger.exhausted)
        assertNull(LauncherExecutionLedger("Organize launcher and export a file").checkTool("run_terminal", emptyMap()))
    }
    @Test fun onlyExactReadCapabilitiesAreReadOnly() {
        assertTrue(OmniLinkActionSemantics.isReadOnly(args("launcher.get_layout")))
        assertTrue(OmniLinkActionSemantics.isReadOnly(mapOf("action" to "discover_capabilities")))
        assertFalse(OmniLinkActionSemantics.isReadOnly(args("launcher.create_home_folder")))
        assertFalse(OmniLinkActionSemantics.isReadOnly(args("launcher.unknown")))
    }
}
