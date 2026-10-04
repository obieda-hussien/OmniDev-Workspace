package com.omnidev.workspace.data.routines

import com.omnidev.workspace.data.tools.ToolExecutionResult
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoutineRuntimeTest {
    private val target = UiSelector("example.app", viewId = "example.app:id/search")
    private fun recipe(id: String = "recipe", steps: List<RoutineStep> = listOf(
        RoutineStep(RoutineStepKind.CLICK, "Open search", target, expected = target)
    )) = LearnedRoutine(id, "Search", listOf("افتح البحث", "Search for {{query}}"), steps, enabled = true)
    private fun store() = RoutineStore(Files.createTempDirectory("routine-test").toFile())

    @Test fun exactArabicAliasSupportsDiacritics() {
        assertNotNull(RoutineMatcher.match("إفتح البَحث", listOf(recipe())))
    }
    @Test fun variableBindingPreservesArabicAndPunctuation() {
        val match = RoutineMatcher.match("Search for عبيدة & café", listOf(recipe()))!!
        assertEquals("عبيدة & café", match.second["query"])
        assertEquals("Look up عبيدة & café", RoutineMatcher.bind("Look up {{query}}", match.second))
    }
    @Test fun partialPhraseDoesNotExecute() { assertNull(RoutineMatcher.match("please افتح البحث then delete", listOf(recipe()))) }
    @Test fun disabledRecipesDoNotExecute() { assertNull(RoutineMatcher.match("افتح البحث", listOf(recipe().copy(enabled = false)))) }
    @Test fun ambiguousAliasesDoNotExecute() { assertNull(RoutineMatcher.match("افتح البحث", listOf(recipe(), recipe("other")))) }
    @Test fun missingVariableFailsBeforeExecution() {
        try { RoutineMatcher.bind("{{query}}", emptyMap()); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun selectorsNeedIdentityAndPackage() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutineValidation.validate(recipe(steps = listOf(RoutineStep(RoutineStepKind.CLICK, "x", UiSelector("example.app")))))
        }
    }
    @Test fun unsafeToolCannotBeReplayed() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutineValidation.validate(recipe(steps = listOf(RoutineStep(RoutineStepKind.TOOL, "x", tool = "terminal_exec"))))
        }
    }
    @Test fun rawCapturedInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutineValidation.validate(recipe(steps = listOf(RoutineStep(RoutineStepKind.TYPE, "x", target, "private input"))))
        }
    }
    @Test fun storeSurvivesNewInstanceAndDeletesCheckpoints() {
        val directory = Files.createTempDirectory("routine-reopen").toFile()
        val storage = RoutineStore(directory); storage.save(recipe())
        storage.saveRun(RoutineRun("run", "recipe", 1, parameters = mapOf("query" to "private-value")))
        assertEquals(recipe().name, RoutineStore(directory).get("recipe")?.name)
        assertTrue(storage.runs().single().parameters.isEmpty())
        storage.delete("recipe"); assertTrue(storage.runs().isEmpty()); assertTrue(storage.list().isEmpty())
    }
    @Test fun rejectsPathTraversalIds() {
        assertThrows(IllegalArgumentException::class.java) { store().save(recipe("../outside")) }
    }
    @Test fun successfulReplayHasNoProviderDependency() = runBlocking {
        val storage = store(); val recipe = recipe(); storage.save(recipe)
        var calls = 0
        val runner = RoutineRunner(storage, { name, args, _ ->
            calls++; assertEquals("semantic_ui", name); assertEquals("routine_click", args["action"])
            assertFalse(args.containsKey("node_id")); ToolExecutionResult("ok")
        }, { true })
        assertEquals(RoutineRunStatus.COMPLETED, runner.start(recipe, emptyMap(), null).status)
        assertEquals(1, calls); assertEquals(1, storage.get(recipe.id)?.successfulRuns)
    }
    @Test fun decisionPausesAndResumeDoesNotRestartEarlierSteps() = runBlocking {
        val storage = store(); val recipe = recipe(steps = listOf(
            RoutineStep(RoutineStepKind.CLICK, "first", target, expected = target),
            RoutineStep(RoutineStepKind.DECISION, "choose", expected = target),
            RoutineStep(RoutineStepKind.CLICK, "last", target, expected = target)))
        storage.save(recipe); var calls = 0
        val runner = RoutineRunner(storage, { _, _, _ -> calls++; ToolExecutionResult("ok") }, { true })
        val paused = runner.start(recipe, emptyMap(), null)
        assertEquals(1, paused.nextStep); assertEquals(1, calls)
        assertEquals(RoutineRunStatus.COMPLETED, runner.resume(paused.id).status); assertEquals(2, calls)
    }
    @Test fun failedMutationIsNeverAutomaticallyRetried() = runBlocking {
        val storage = store(); val recipe = recipe(); storage.save(recipe); var calls = 0
        val runner = RoutineRunner(storage, { _, _, _ -> calls++; ToolExecutionResult("unknown", true) }, { false })
        val paused = runner.start(recipe, emptyMap(), null)
        assertEquals(RoutineRunStatus.PAUSED, paused.status); assertEquals(0, paused.nextStep)
        runner.resume(paused.id); assertEquals(1, calls)
    }
    @Test fun noTaughtOutcomePausesEvenWhenClickAccepted() = runBlocking {
        val storage = store(); val recipe = recipe(steps = listOf(RoutineStep(RoutineStepKind.CLICK, "click", target))); storage.save(recipe)
        val runner = RoutineRunner(storage, { _, _, _ -> ToolExecutionResult("accepted") }, { true })
        assertEquals(RoutineRunStatus.PAUSED, runner.start(recipe, emptyMap(), null).status)
    }
    @Test fun processRecreatedRunnerNeedsParametersAgain() = runBlocking {
        val storage = store(); val recipe = recipe(steps = listOf(RoutineStep(RoutineStepKind.USER, "manual"),
            RoutineStep(RoutineStepKind.TYPE, "input", target, "{{query}}"))); storage.save(recipe)
        val paused = RoutineRunner(storage, { _, _, _ -> ToolExecutionResult("ok") }, { false }).start(recipe, mapOf("query" to "private"), null)
        // Persistent checkpoints never contain parameter values.
        assertTrue(storage.runs().single().parameters.isEmpty())
        assertEquals(RoutineRunStatus.PAUSED, paused.status)
    }
    @Test fun changedRecipeCannotResumeOldCursor() = runBlocking {
        val storage = store(); val recipe = recipe(steps = listOf(RoutineStep(RoutineStepKind.USER, "manual"))); storage.save(recipe)
        val runner = RoutineRunner(storage, { _, _, _ -> ToolExecutionResult("ok") }, { false })
        val paused = runner.start(recipe, emptyMap(), null); storage.save(recipe.copy(revision = 2))
        try { runner.resume(paused.id, userCompletedStep = true); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun cancellationLeavesUncertainStepPaused() = runBlocking {
        val storage = store(); val recipe = recipe(); storage.save(recipe)
        val runner = RoutineRunner(storage, { _, _, _ -> throw CancellationException("cancel") }, { true })
        try { runner.start(recipe, emptyMap(), null); fail() } catch (_: CancellationException) { }
        val checkpoint = storage.runs().single()
        assertEquals(RoutineRunStatus.PAUSED, checkpoint.status); assertTrue(checkpoint.inFlight)
    }
    @Test fun launchingAppsIsReplayableButClearingDataIsNot() {
        assertTrue(RoutineValidation.replayableTool("app_manager_tool", mapOf("action" to "launch_app", "packageName" to "example.app")))
        assertFalse(RoutineValidation.replayableTool("app_manager_tool", mapOf("action" to "clear_data", "packageName" to "example.app")))
    }
    @Test fun omniLinkReplayRequiresPinnedExtension() {
        assertFalse(RoutineValidation.replayableTool("omni_link", mapOf("action" to "execute_capability", "action_name" to "launcher.health")))
        assertTrue(RoutineValidation.replayableTool("omni_link", mapOf("action" to "execute_action", "extension_id" to "example.app/service", "action_name" to "launcher.health")))
    }
    @Test fun pausingBeforeAnActionResumesThatActionRatherThanSkippingIt() = runBlocking {
        val storage = store(); val recipe = recipe(); storage.save(recipe)
        var calls = 0; var first = true
        lateinit var runner: RoutineRunner
        runner = RoutineRunner(storage, { _, _, _ -> calls++; ToolExecutionResult("ok") }, { true }, {
            if (first) { first = false; runner.pause() }
        })
        val paused = runner.start(recipe, emptyMap(), null)
        assertFalse(paused.inFlight); assertEquals(0, calls)
        assertEquals(RoutineRunStatus.COMPLETED, runner.resume(paused.id).status); assertEquals(1, calls)
    }
    @Test fun malformedOmniPayloadPausesBeforeCallingTheExtension() = runBlocking {
        val storage = store()
        val recipe = recipe(steps = listOf(RoutineStep(RoutineStepKind.TOOL, "extension", tool = "omni_link",
            arguments = mapOf("action" to "execute_action", "extension_id" to "example.app/service", "action_name" to "update", "json_payload" to "{{payload}}"))))
        storage.save(recipe); var calls = 0
        val runner = RoutineRunner(storage, { _, _, _ -> calls++; ToolExecutionResult("ok") }, { true })
        assertEquals(RoutineRunStatus.PAUSED, runner.start(recipe, mapOf("payload" to "invalid JSON"), null).status)
        assertEquals(0, calls)
    }

    @Test fun dynamicSelectorTextUsesRuntimeBinding() = runBlocking {
        val storage = store()
        val recipe = recipe(steps = listOf(RoutineStep(RoutineStepKind.WAIT, "recipient", target.copy(text = "{{recipient}}"))))
        storage.save(recipe)
        var selector = ""
        val runner = RoutineRunner(storage, { _, args, _ -> selector = args["selector"].orEmpty(); ToolExecutionResult("ok") }, { true })
        assertEquals(setOf("recipient"), RoutineMatcher.required(recipe))
        assertEquals(RoutineRunStatus.COMPLETED, runner.start(recipe, mapOf("recipient" to "عبيدة"), null).status)
        assertTrue(selector.contains("عبيدة")); assertFalse(selector.contains("{{recipient}}"))
    }

    @Test fun compiledDemoHasAnExplicitReviewedEntryPoint() {
        val steps = listOf(RoutineStep(RoutineStepKind.CLICK, "Search", target))
        val compiled = RoutineCompilation.withEntryPoint(steps)
        assertEquals("app_manager_tool", compiled.first().tool)
        assertEquals("example.app", compiled.first().arguments["packageName"])
        assertEquals(steps.first(), compiled.last())
        assertEquals(compiled, RoutineCompilation.withEntryPoint(compiled))
    }

    @Test fun staleEditsCannotReuseAnExistingRunRevision() {
        val storage = store(); val original = recipe(); storage.save(original)
        storage.save(original.copy(name = "First edit", revision = 2))
        storage.save(original.copy(name = "Stale editor", revision = 2))
        assertEquals(3, storage.get(original.id)?.revision)
    }

}
