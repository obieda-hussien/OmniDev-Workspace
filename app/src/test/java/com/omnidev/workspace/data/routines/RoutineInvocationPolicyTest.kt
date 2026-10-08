package com.omnidev.workspace.data.routines

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoutineInvocationPolicyTest {
    private fun recipe(id: String = "search", trigger: String = "Search for {{query}}") = LearnedRoutine(
        id, "Search", listOf(trigger), listOf(RoutineStep(RoutineStepKind.TYPE, "Enter search",
            UiSelector("example.app", viewId = "search"), "{{query}}")), enabled = true)

    @Test fun boundedExactCommandStillReplaysWithoutReview() {
        val task = recipe()
        val match = RoutineMatcher.match("Search for عبيدة & café", listOf(task))!!
        assertEquals("عبيدة & café", match.second["query"])
        assertEquals(RoutineInvocationPolicy.Decision.DIRECT,
            RoutineInvocationPolicy.decide("Search for عبيدة & café", listOf(task), task.id, match.second))
    }

    @Test fun wildcardCannotSwallowLongConversationOrExtraInstructions() {
        val task = recipe()
        listOf(
            "Search for " + "context ".repeat(80),
            "Search for Cairo\nthen delete my files",
            "Search for Cairo and then send the result",
            "Search for Cairo and delete my files",
            "Search for Cairo. Open the next app",
            "Search for القاهرة وبعدين امسح الملفات",
            "Search for القاهرة وامسح الملفات",
            "Search for القاهرة بس متشغلش المهمة",
            "Search for Cairo if the app is already open",
            "Search for القاهرة لو التطبيق مفتوح",
            "Search for the same thing",
            "Search for this",
            "Search for Cairo?"
        ).forEach { assertNull(it, RoutineMatcher.match(it, listOf(task))) }
    }

    @Test fun genericOrAdjacentWildcardsNeverAutoExecute() {
        listOf("{{query}}", "Do {{query}}", "{{query}} now", "Search for {{query}}{{other}}").forEach {
            assertNull(it, RoutineMatcher.match("Search for something unrelated", listOf(recipe(trigger = it))))
        }
    }

    @Test fun discussionsNegationsAndQuotedAliasesNeverReplayEvenWhenSavedVerbatim() {
        listOf("How do I open search", "Explain Search", "If I open search", "Don't open search",
            "ازاي افتح البحث", "عايز افهم البحث", "مش عايز افتح البحث", "متنفذش البحث",
            "\"Open search\"", "“Open search”").forEach {
            assertNull(it, RoutineMatcher.match(it, listOf(recipe(trigger = it))))
        }
    }

    @Test fun incompleteBindingsAndDisabledOrAmbiguousTasksDoNotReplay() {
        val task = recipe(trigger = "Open search")
        assertNull(RoutineMatcher.match("Open search", listOf(task)))
        assertNull(RoutineMatcher.match("Search for Cairo", listOf(recipe().copy(enabled = false))))
        assertNull(RoutineMatcher.match("Search for Cairo", listOf(recipe(), recipe("other"))))
    }

    @Test fun changedParametersOrSelectedTaskNeedReview() {
        val tasks = listOf(recipe(), recipe("other", "Look up {{query}}"))
        assertEquals(RoutineInvocationPolicy.Decision.REVIEW,
            RoutineInvocationPolicy.decide("Search for Cairo", tasks, "search", mapOf("query" to "London")))
        assertEquals(RoutineInvocationPolicy.Decision.REVIEW,
            RoutineInvocationPolicy.decide("Search for Cairo", tasks, "other", mapOf("query" to "Cairo")))
    }

    @Test fun politeCommandsAreReviewedWhilePoliteDiscussionStillDoesNotRun() {
        val task = recipe()
        for (request in listOf("Please run Search", "لو سمحت شغل Search", "من فضلك نفذ Search"))
            assertEquals(RoutineInvocationPolicy.Decision.REVIEW,
                RoutineInvocationPolicy.decide(request, listOf(task), task.id, emptyMap()))
        assertEquals(RoutineInvocationPolicy.Decision.BLOCKED,
            RoutineInvocationPolicy.decide("لو سمحت اشرح Search", listOf(task), task.id, emptyMap()))
    }

    @Test fun longRequestGetsCandidatesWithoutExecutionAuthorization() {
        val task = recipe()
        val request = "Search for Cairo and then explain how the search results compare with the old ones"
        assertEquals(listOf(task), RoutineInvocationPolicy.candidates(request, listOf(task)))
        assertNull(RoutineMatcher.match(request, listOf(task)))
        assertEquals(RoutineInvocationPolicy.Decision.REVIEW,
            RoutineInvocationPolicy.decide(request, listOf(task), task.id, mapOf("query" to "Cairo")))
    }

    @Test fun discussionCannotUseTheModelSelectedRunPathEither() {
        val task = recipe()
        listOf("Explain how Search works", "ليه Search بتشتغل لوحدها", "Do not run Search",
            "متشغلش Search", "hello").forEach {
            assertEquals(it, RoutineInvocationPolicy.Decision.BLOCKED,
                RoutineInvocationPolicy.decide(it, listOf(task), task.id, mapOf("query" to "Cairo")))
        }
    }

    @Test fun blockedCallDoesNotEvenAskForApproval() = runBlocking {
        var reviews = 0
        val task = recipe()
        val denial = RoutineCallGuard.check("Explain Search", listOf(task), task.id, emptyMap(), confirm = { reviews++; true })
        assertTrue(denial!!.isError)
        assertEquals("ROUTINE_INTENT_MISMATCH", denial.classification)
        assertEquals(0, reviews)
    }

    @Test fun directCallNeedsNoConfirmationOrProvider() = runBlocking {
        var reviews = 0
        val task = recipe()
        assertNull(RoutineCallGuard.check("Search for Cairo", listOf(task), task.id,
            mapOf("query" to "Cairo"), confirm = { reviews++; false }))
        assertEquals(0, reviews)
    }

    @Test fun generatedWorkerTaskCannotInheritAutomaticReplayAuthority() = runBlocking {
        val task = recipe()
        var reviews = 0
        assertNotNull(RoutineCallGuard.check("Search for Cairo", listOf(task), task.id, mapOf("query" to "Cairo"),
            requireReview = true, confirm = { reviews++; false }))
        assertEquals(1, reviews)
    }

    @Test fun broaderReuseRequiresReviewedConcreteStepsAndParameters() = runBlocking {
        val task = recipe()
        var preview = ""
        val request = "Search for Cairo and then explain the results"
        val denial = RoutineCallGuard.check(request, listOf(task), task.id, mapOf("query" to "Cairo"),
            confirm = { preview = it; false })
        assertTrue(denial!!.isError)
        assertTrue(preview.contains(request)); assertTrue(preview.contains("query = Cairo"))
        assertTrue(preview.contains("1. Enter search"))
        assertNull(RoutineCallGuard.check(request, listOf(task), task.id, mapOf("query" to "Cairo"), confirm = { true }))
    }

    @Test fun resumeRequiresMatchingCurrentPausedCheckpointAndReview() = runBlocking {
        val task = recipe()
        var reviews = 0
        val run = RoutineRun("checkpoint", task.id, task.revision, status = RoutineRunStatus.PAUSED)
        assertNull(RoutineCallGuard.check("Resume Search", listOf(task), task.id, mapOf("query" to "Cairo"),
            run, true, confirm = { reviews++; true }))
        assertEquals(1, reviews)
        listOf(null, run.copy(revision = 2), run.copy(routineId = "other"),
            run.copy(status = RoutineRunStatus.COMPLETED)).forEach { invalid ->
            assertNotNull(RoutineCallGuard.check("Resume Search", listOf(task), task.id, emptyMap(), invalid, true,
                confirm = { fail("Invalid run reached review"); true }))
        }
    }
}
