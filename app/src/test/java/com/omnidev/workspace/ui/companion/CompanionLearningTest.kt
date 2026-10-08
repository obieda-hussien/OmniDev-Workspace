package com.omnidev.workspace.ui.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionLearningTest {
    private fun context() = CompanionMindContext(hasConsole = true, hasMessages = true)
    private fun trained(): CompanionLearner = CompanionLearner().apply {
        repeat(160) {
            learn(policy().evaluate(context(), CompanionAction.LEFT), 1f)
            learn(policy().evaluate(context(), CompanionAction.RIGHT), 0f)
        }
    }
    private fun advance(mind: CompanionMind, seconds: Float) = repeat((seconds * 4).toInt()) { mind.step(.25f) }

    @Test fun analyticGradientMatchesFiniteDifferences() {
        val network = CompanionNetwork(4, 3, 42)
        val x = floatArrayOf(.2f, -.1f, .8f, .4f)
        val gradient = network.evaluate(x).second
        for (i in network.weights.indices) {
            val old = network.weights[i]
            network.weights[i] = old + .001f; val high = network.evaluate(x).first
            network.weights[i] = old - .001f; val low = network.evaluate(x).first
            network.weights[i] = old
            assertEquals((high - low) / .002f, gradient[i], .0005f)
        }
    }
    @Test fun bothNetworksActuallyUpdateFromFeedback() {
        val learner = CompanionLearner(); val before = learner.policy()
        learner.learn(before.evaluate(context(), CompanionAction.SNIFF), 1f)
        val after = learner.policy()
        assertFalse(before.exploitation.weights.contentEquals(after.exploitation.weights))
        assertFalse(before.exploration.weights.contentEquals(after.exploration.weights))
        assertEquals(1, learner.observations)
    }
    @Test fun positiveAndNegativeExamplesChangeWhichActionIsChosen() {
        val policy = trained().policy()
        val left = policy.evaluate(context(), CompanionAction.LEFT)
        val right = policy.evaluate(context(), CompanionAction.RIGHT)
        assertTrue("${left.score} vs ${right.score}", left.score > right.score + .25f)
        assertEquals(CompanionAction.LEFT, policy.choose(context(), listOf(CompanionAction.RIGHT, CompanionAction.LEFT))!!.action)
    }
    @Test fun explorationLearnsTheSignedPreUpdateResidual() {
        val learner = CompanionLearner(); val decision = learner.policy().evaluate(context(), CompanionAction.PEEK)
        val before = learner.policy().exploration.evaluate(decision.gradient).first
        learner.learn(decision, 0f)
        val after = learner.policy().exploration.evaluate(decision.gradient).first
        assertTrue(after < before)
        assertEquals(decision.prediction, learner.experiences.single().decision.prediction, 0f)
    }
    @Test fun projectedGradientIsFiniteAndNormalized() {
        val decision = CompanionPolicy().evaluate(context(), CompanionAction.MESSAGE)
        assertEquals(CompanionPolicy.SKETCH, decision.gradient.size)
        assertTrue(decision.gradient.all(Float::isFinite))
        assertEquals(1.0, decision.gradient.sumOf { (it * it).toDouble() }, .0001)
    }
    @Test fun unavailableActionsCannotBeSelected() {
        val policy = trained().policy()
        assertEquals(CompanionAction.RIGHT, policy.choose(context(), listOf(CompanionAction.RIGHT))!!.action)
        assertNull(policy.choose(context(), emptyList()))
    }
    @Test fun savedWeightsAndPredictionsSurviveAProcessRestart() {
        val learner = trained(); learner.feeling = CompanionFeeling(.4f, .8f, .6f, .2f, .1f); learner.savedAt = 1234
        val restored = CompanionLearner.decode(learner.encode())!!
        assertEquals(learner.observations, restored.observations)
        assertEquals(learner.feeling, restored.feeling)
        assertEquals(1234L, restored.savedAt)
        for (action in CompanionAction.entries) assertEquals(
            learner.policy().evaluate(context(), action).score, restored.policy().evaluate(context(), action).score, 0f)
        assertEquals(CompanionLearner.CAPACITY, restored.experiences.size)
    }
    @Test fun resumedTrainingUsesPersistedExperience() {
        val restored = CompanionLearner.decode(trained().encode())!!
        val before = restored.policy().exploitation.weights.copyOf()
        restored.learn(restored.policy().evaluate(context(), CompanionAction.STRETCH), .7f)
        assertFalse(before.contentEquals(restored.policy().exploitation.weights))
        assertEquals(321, restored.observations)
    }
    @Test fun replayAndCheckpointHaveABoundedSize() {
        val learner = trained()
        assertEquals(CompanionLearner.CAPACITY, learner.experiences.size)
        assertTrue(learner.encode().size < CompanionLearner.MAX_BYTES)
    }
    @Test fun corruptedTruncatedAndOversizedMemoriesAreRejected() {
        val bytes = trained().encode()
        assertNull(CompanionLearner.decode(bytes.copyOf(15)))
        assertNull(CompanionLearner.decode(ByteArray(CompanionLearner.MAX_BYTES + 1)))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertNull(CompanionLearner.decode(bytes))
    }
    @Test fun nonFiniteFeedbackCannotPoisonTheLearner() {
        val learner = CompanionLearner(); val decision = learner.policy().evaluate(context(), CompanionAction.PEEK)
        learner.learn(decision, Float.NaN)
        learner.learn(decision.copy(features = decision.features.copyOf().also { it[0] = Float.POSITIVE_INFINITY }), 1f)
        assertEquals(0, learner.observations)
    }
    @Test fun immutablePolicySnapshotsDoNotChangeDuringBackgroundTraining() {
        val learner = CompanionLearner(); val snapshot = learner.policy()
        val before = snapshot.evaluate(context(), CompanionAction.PEEK).score
        repeat(20) { learner.learn(learner.policy().evaluate(context(), CompanionAction.PEEK), 1f) }
        assertEquals(before, snapshot.evaluate(context(), CompanionAction.PEEK).score, 0f)
    }
    @Test fun noFeedbackAndOrdinaryTouchesNeverBecomeTrainingLabels() {
        var learned = 0
        val mind = CompanionMind(learn = { _, _ -> learned++ })
        mind.context(context()); mind.choose(listOf(CompanionAction.PEEK)); mind.tap(); advance(mind, 30f)
        assertEquals(0, learned)
        assertFalse(mind.feedback(true))
    }
    @Test fun explicitFeedbackIsAttributedOnceToTheChosenAction() {
        val samples = mutableListOf<CompanionExperience>()
        val mind = CompanionMind(learn = { d, r -> samples.add(CompanionExperience(d, r)) })
        mind.context(context()); mind.choose(listOf(CompanionAction.PEEK))
        assertTrue(mind.feedback(false)); assertFalse(mind.feedback(true))
        assertEquals(CompanionAction.PEEK, samples.single().decision.action)
        assertEquals(0f, samples.single().reward, 0f)
    }
    @Test fun disabledLearningNeitherUsesTheModelNorUpdatesIt() {
        var reads = 0; var writes = 0
        val mind = CompanionMind(policy = { reads++; CompanionPolicy() }, learn = { _, _ -> writes++ })
        mind.learning = false; mind.context(context())
        assertNull(mind.choose(listOf(CompanionAction.PEEK)))
        mind.pickup(); mind.placed(.1f, true)
        assertFalse(mind.feedback(true)); assertEquals(0, reads); assertEquals(0, writes)
    }
    @Test fun deliberatePlacementTeachesPhysicalSideButFlingAndCancelDoNot() {
        val actions = mutableListOf<CompanionAction>()
        val mind = CompanionMind(learn = { d, _ -> actions.add(d.action) })
        mind.context(context()); mind.pickup(); mind.placed(.1f, true)
        mind.pickup(); mind.placed(.9f, true)
        mind.pickup(); mind.tossed(); mind.placed(.1f, true)
        mind.pickup(); mind.cancelledPickup(); mind.placed(.1f, true)
        assertEquals(listOf(CompanionAction.LEFT, CompanionAction.RIGHT), actions)
    }
    @Test fun repeatedQuickTouchesLeadToGuardedThenAnnoyedExpression() {
        val mind = CompanionMind()
        assertTrue(mind.tap()); assertTrue(mind.tap())
        assertFalse(mind.tap()); assertEquals(CompanionMood.GUARDED, mind.mood)
        assertFalse(mind.tap()); assertEquals(CompanionMood.ANNOYED, mind.mood)
    }
    @Test fun spacedPlayDoesNotAccumulateAnger() {
        val mind = CompanionMind()
        repeat(10) { assertTrue(mind.tap()); advance(mind, 1f) }
        assertFalse(mind.wantsSpace)
    }
    @Test fun irritationAndSadnessSettleWithoutDemandingInteraction() {
        val mind = CompanionMind(CompanionFeeling(irritation = 1f, sadness = 1f))
        advance(mind, 180f)
        assertFalse(mind.wantsSpace); assertNull(mind.mood)
    }
    @Test fun savedFamiliarityOutlastsMoodsDuringAbsence() {
        val feeling = CompanionFeeling(.2f, .9f, 1f, 1f, 1f).afterAbsence(3600f)
        assertEquals(.9f, feeling.familiarity, 0f)
        assertEquals(.7f, feeling.energy, .001f)
        assertTrue(feeling.irritation < .001f && feeling.sadness < .001f && feeling.joy < .001f)
    }
    @Test fun hidingAndRescueKeepTheExistingHappyReturnContract() {
        val mind = CompanionMind(CompanionFeeling(irritation = 1f))
        mind.hidden(); mind.welcomed()
        assertFalse(mind.wantsSpace); assertEquals(CompanionMood.HAPPY, mind.mood)
        assertEquals(0f, mind.feeling.sadness, 0f)
    }
    @Test fun expressionToggleKeepsControlOverDisplayedMoods() {
        val mind = CompanionMind(CompanionFeeling(irritation = 1f, sadness = 1f))
        mind.expressive = false
        assertFalse(mind.wantsSpace); assertNull(mind.mood)
    }
    @Test fun lateDiskRestoreDoesNotEraseAnImmediateTouch() {
        val mind = CompanionMind(); mind.tap()
        mind.restore(CompanionFeeling(familiarity = .8f, irritation = .5f))
        assertEquals(CompanionMood.HAPPY, mind.mood)
        assertTrue(mind.feeling.familiarity > .8f)
    }
    @Test fun sessionsRetainFamiliarityAndLearnedChoices() {
        val learner = trained(); var remembered = CompanionFeeling()
        val first = CompanionMind(policy = learner::policy, remember = { remembered = it })
        first.tap(); first.flush()
        val next = CompanionMind(initial = remembered, policy = learner::policy)
        next.context(context())
        assertEquals(first.feeling.familiarity, next.feeling.familiarity, 0f)
        assertEquals(CompanionAction.LEFT, next.choose(listOf(CompanionAction.LEFT, CompanionAction.RIGHT)))
    }
}
