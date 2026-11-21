package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.model.ToolCallResult
import com.omnidev.workspace.domain.engine.completion.*
import com.omnidev.workspace.domain.planning.PlanningTrigger
import org.junit.Assert.*
import org.junit.Test

class DeviceWorkflowTest {
    private val goal = "عايز تلخص الاشعارات و بعدين تفتح اليوتيوب و تدور علي ملخص فيلم بالخمسينة بس فيلم يكون حلو و علي ذوقي"
    private val ledger = RunEvidenceLedger("run")
    private fun record(name: String, args: Map<String, String>, error: Boolean = false) {
        val call = ToolCall("t" + ledger.size(), name, args)
        ledger.record(call, ToolCallResult(call.id, name, if (error) "Unavailable" else "Observed result", error))
    }

    @Test fun launchAndFailedSearchCannotFinishTheWorkflow() {
        assertFalse(DeviceWorkflow.tracked("How do I search YouTube?"))
        assertTrue(PlanningTrigger.complex(goal))
        assertTrue(DeviceWorkflow.notificationsFirst(goal))
        val gate = FinishGate(ledger, RunState(goal, ledger, emptyList(), true), FinalizationPolicy(true, true), RunIdentity("run"))
        record("app_manager_tool", mapOf("action" to "launch_app", "packageName" to "com.google.android.youtube"))
        assertEquals(2, DeviceWorkflow.missing(goal, ledger.snapshot()).size)
        record("read_notifications", emptyMap())
        record("social_media_video", mapOf("action" to "search_youtube"), true)
        assertTrue(gate.implicit("All done") is GateResult.Reject)
        record("social_media_video", mapOf("action" to "search_youtube"))
        assertTrue(DeviceWorkflow.missing(goal, ledger.snapshot()).isEmpty())
    }

    @Test fun followupKeepsTheQueryWithoutRequestingAnotherLaunch() {
        val follow = ExecutionEnvironmentContext.effectiveObjective("طب انت شغلت التطبيق بس مبحستش، ابحثلي يالا", listOf(ChatMessage(MessageRole.USER, goal)))
        assertTrue(DeviceWorkflow.youtubeSearch(follow))
        assertTrue(follow.contains("بالخمسينة"))
        assertEquals(1, DeviceWorkflow.missing(follow, emptyList()).size)
    }

    @Test fun notificationSearchAndAlarmRequireDistinctReceipts() {
        val request = "عايزك تلخص الاشعارات و بعدين تبحث في جوجل عن احدث الافلام و بعدين ظبط منبه الساعه ٧ بليل"
        assertEquals(3, DeviceWorkflow.orderedParts(request).size)
        assertEquals(listOf("read_notifications", "web_search", "planner_tool"), ExecutionEnvironmentContext.priorityNames(request, null))
        record("read_notifications", mapOf("operation" to "summary"))
        record("web_scraper", emptyMap())
        record("planner_tool", mapOf("action" to "show_alarms"))
        assertEquals(3, DeviceWorkflow.missing(request, ledger.snapshot()).size)
        record("read_notifications", mapOf("operation" to "read"))
        record("web_search", mapOf("query" to "latest movies"))
        record("planner_tool", mapOf("action" to "alarm"), true)
        assertEquals(1, DeviceWorkflow.missing(request, ledger.snapshot()).size)
        record("planner_tool", mapOf("action" to "alarm", "localTime" to "19:00"))
        assertTrue(DeviceWorkflow.missing(request, ledger.snapshot()).isEmpty())
    }

    @Test fun webSearchRequiresAnExplicitDestination() {
        assertTrue(DeviceWorkflow.needsWeb("Search on Google for movie releases"))
        assertTrue(DeviceWorkflow.needsWeb("Search the web for movie releases"))
        assertTrue(DeviceWorkflow.needsWeb("تبحث في جوجل عن احدث الافلام"))
        assertFalse(DeviceWorkflow.needsWeb("Search YouTube for Google I/O talks"))
        assertFalse(DeviceWorkflow.needsWeb("Search the codebase for Google API usages"))
        assertFalse(DeviceWorkflow.needsWeb("ابحث في يوتيوب عن جوجل"))
    }

    @Test fun spotifyLaunchOrGenericPlayCannotVerifyTheRequestedTrack() {
        val request = "افتح سبوتيفاي و شغل اغنية انا مش اناني لعمر دياب"
        val track = SpotifyPlayback.requested(request)!!
        val observation = "  1. com.spotify.music\n     Title: أنا مش أناني\n     Artist: عمرو دياب\n     Status: ▶️ Playing"
        assertTrue(SpotifyPlayback.verified(track, observation))
        assertFalse(SpotifyPlayback.verified(track, observation.replace("Playing", "Paused")))
        assertFalse(SpotifyPlayback.verified(track, observation.replace("عمرو دياب", "Other artist")))
        assertFalse(SpotifyPlayback.verified(track, observation.replace("com.spotify.music", "com.android.music")))
        val history = listOf(ChatMessage(MessageRole.USER, request))
        val continuation = "طب ما تتحكم انت في التلفون و شغلها"
        val grounded = ExecutionEnvironmentContext.effectiveObjective(continuation, history)
        assertTrue(grounded.startsWith(continuation + "\n"))
        assertEquals(track, SpotifyPlayback.requested(grounded))
        for (newRequest in listOf("Don't play it", "Play it on YouTube instead", "متشغلهاش", "Use semantic_ui to inspect this screen")) {
            assertEquals(newRequest, ExecutionEnvironmentContext.effectiveObjective(newRequest, history))
        }
        val cancelled = history + ChatMessage(MessageRole.USER, "Don't play it")
        assertEquals("Play it", ExecutionEnvironmentContext.effectiveObjective("Play it", cancelled))
        assertNotNull(DeviceWorkflow.orderError(request, emptyList(),
            listOf(ToolCall("play", "media_control", mapOf("action" to "play")))))
    }

    @Test fun groundedContinuationUsesOrderedReceiptsAndDoesNotRepeatTheAlarm() {
        val request = "عايزك تلخص الاشعارات و بعدين تبحث في جوجل عن احدث الافلام الاجنبيه و بعدين ظبط منبه الساعه ٧ بليل"
        val catalog = RunToolCatalog(listOf("read_notifications", "web_search", "planner_tool").map {
            com.omnidev.workspace.data.tools.ToolDefinition(it, it, emptyList())
        }, request)
        fun next() = GroundedContinuation.recover(com.omnidev.workspace.data.model.CompletionResponse(""),
            request, ledger.snapshot(), catalog, ledger.size() + 1).toolCalls.singleOrNull()
        assertEquals("read_notifications", next()?.name)
        record("read_notifications", mapOf("operation" to "read"))
        assertEquals("web_search", next()?.name)
        assertTrue(next()?.arguments?.get("query")?.contains("الافلام") == true)
        record("web_search", mapOf("query" to "movies"))
        assertEquals(mapOf("action" to "alarm", "localTime" to "19:00"), next()?.arguments)
        record("planner_tool", mapOf("action" to "alarm"), true)
        assertNull(next())
    }

    @Test fun identityAndInventoryComeFromTheRuntime() {
        val catalog = RunToolCatalog(listOf(com.omnidev.workspace.data.tools.ToolDefinition(
            "read_file", "Read a file", emptyList())), "inventory")
        assertTrue(OmniIdentityReply.reply("انت مين و بتعمل اي؟", null, catalog)!!.startsWith("أنا Omni"))
        assertTrue(OmniIdentityReply.reply("اي الادوات اللي عندك كلها", null, catalog)!!.contains("read_file"))
        assertFalse(OmniIdentityReply.reply("و عندك اي تاني غير دول", "اي الادوات اللي عندك كلها", catalog)!!
            .contains("privileged_tool"))
    }

    @Test fun missingIconIsNotAScreenshot() {
        val request = "جرب تاخد اسكرين شوت بعد كده مع الاداة دي"
        record("semantic_ui", mapOf("action" to "find_element"), true)
        assertEquals(1, DeviceWorkflow.missing(request, ledger.snapshot()).size)
        record("screenshot_tool", emptyMap())
        assertTrue(DeviceWorkflow.missing(request, ledger.snapshot()).isEmpty())
    }
}
