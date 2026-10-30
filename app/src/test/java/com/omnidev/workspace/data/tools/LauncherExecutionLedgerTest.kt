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

    @Test fun explanatoryAndProjectGoalsCanCompleteWithoutChangingHome() {
        for (goal in listOf("How do I organize my launcher?", "Create a launcher app project", "Implement launcher folder organization in the repository", "اشرح ازاي ارتب التطبيقات", "اعمل مشروع لانشر")) {
            val ledger = LauncherExecutionLedger(goal)
            assertFalse(goal, ledger.requiresAction)
            assertNull(ledger.checkTool("write_file", emptyMap()))
            assertNull(ledger.completionFeedback())
        }
        assertTrue(LauncherExecutionLedger("Can you organize my launcher?").requiresAction)
    }
    @Test fun explicitHomeFolderWordingRejectsDrawerSubstitution() {
        val ledger = LauncherExecutionLedger("Create home folders in my launcher")
        ledger.observe("omni_link", args("launcher.create_folder"), outcome(JSONObject().put("folderId", 5)), false)
        assertNotNull(ledger.completionFeedback())
        ledger.observe("omni_link", args("launcher.create_home_folder"), outcome(JSONObject().put("reloadScheduled", true)), false)
        ledger.observe("omni_link", args("launcher.get_layout"), outcome(JSONObject().put("revision", "new")), false)
        assertNull(ledger.completionFeedback())
    }
    @Test fun steeringPreservesCommittedHomeMutationAndRequiresReadback() {
        val original = LauncherExecutionLedger("Create home folders in my launcher")
        original.observe("omni_link", args("launcher.create_home_folder"), outcome(JSONObject().put("reloadScheduled", true)), false)
        val redirected = original.redirected("Create home folders in my launcher and keep the dock unchanged")
        assertTrue(redirected.completionFeedback()!!.contains("already succeeded"))
        redirected.observe("omni_link", args("launcher.get_layout"), outcome(JSONObject().put("revision", "new")), false)
        assertNull(redirected.completionFeedback())
    }

    @Test fun homeMutationsRequireLatestExpectedRevision() {
        val ledger = LauncherExecutionLedger("Organize launcher home screen")
        val layoutArgs = mapOf(
            "action" to "execute_capability",
            "action_name" to "launcher.get_layout",
            "json_payload" to "{}"
        )
        ledger.observe("omni_link", layoutArgs,
            outcome(JSONObject().put("revision", "rev-1")), false)

        val base = mapOf("action" to "execute_capability", "action_name" to "launcher.set_grid")
        assertNotNull(ledger.checkTool("omni_link", base + ("json_payload" to """{"revision":"rev-1","rows":5,"columns":5}""")))
        assertNotNull(ledger.checkTool("omni_link", base + ("json_payload" to """{"rows":5,"columns":5}""")))
        assertNull(ledger.checkTool("omni_link", base + ("json_payload" to """{"expectedRevision":"rev-1","rows":5,"columns":5}""")))
        assertNotNull(ledger.checkTool("omni_link", base + ("json_payload" to """{"expectedRevision":"stale","rows":5,"columns":5}""")))
    }

    @Test fun followupMutationRequiresFreshReadAfterGridReload() {
        val ledger = LauncherExecutionLedger("Organize launcher home screen")
        ledger.observe("omni_link",
            mapOf("action" to "execute_capability", "action_name" to "launcher.get_layout", "json_payload" to "{}"),
            outcome(JSONObject().put("revision", "rev-1")), false)
        ledger.observe("omni_link",
            mapOf("action" to "execute_capability", "action_name" to "launcher.set_grid",
                "json_payload" to """{"expectedRevision":"rev-1","rows":5,"columns":5}"""),
            outcome(JSONObject().put("reloadScheduled", true)), false)

        val folderArgs = mapOf("action" to "execute_capability", "action_name" to "launcher.create_home_folder",
            "json_payload" to """{"expectedRevision":"rev-1","title":"Tools","itemIds":[22,33]}""")
        assertNotNull(ledger.checkTool("omni_link", folderArgs))

        ledger.observe("omni_link",
            mapOf("action" to "execute_capability", "action_name" to "launcher.get_layout", "json_payload" to "{}"),
            outcome(JSONObject().put("revision", "rev-2")), false)
        val freshFolderArgs = folderArgs + ("json_payload" to """{"expectedRevision":"rev-2","title":"Tools","itemIds":[22,33]}""")
        assertNull(ledger.checkTool("omni_link", freshFolderArgs))
    }

    @Test fun paginatedLayoutMustBeFullyReadBeforeHomeMutation() {
        val ledger = LauncherExecutionLedger("Organize launcher home screen")
        val layoutArgs = mapOf(
            "action" to "execute_capability",
            "action_name" to "launcher.get_layout",
            "json_payload" to "{}"
        )
        ledger.observe("omni_link", layoutArgs,
            outcome(JSONObject().put("revision", "rev-1").put("total", 56).put("nextOffset", 40)), false)

        val mutationArgs = mapOf(
            "action" to "execute_capability",
            "action_name" to "launcher.set_grid",
            "json_payload" to """{"expectedRevision":"rev-1","rows":5,"columns":5}"""
        )
        val blocked = ledger.checkTool("omni_link", mutationArgs)
        assertNotNull(blocked)
        assertTrue(blocked!!.contains("offset=40"))

        ledger.observe("omni_link",
            layoutArgs + ("json_payload" to """{"offset":40}"""),
            outcome(JSONObject().put("revision", "rev-1").put("total", 56).put("nextOffset", JSONObject.NULL)), false)
        assertNull(ledger.checkTool("omni_link", mutationArgs))
    }
}
