package com.omnidev.workspace.ui.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionMotionTest {
    private val scene = CompanionScene(360f, 600f, 60f, CompanionPerch(12f, 348f, 520f), CompanionPerch(20f, 340f, 270f))
    private fun engine() = CompanionMotion().apply { configure(scene) }
    private fun advance(engine: CompanionMotion, seconds: Float, working: Boolean = false, roaming: Boolean = true, reduced: Boolean = false) {
        repeat((seconds / .025f).toInt()) { engine.step(.025f, working, roaming, reduced) }
    }

    @Test fun feetStartAboveTheActualEditor() {
        val e = engine()
        assertEquals(460f, e.pose.y, .001f)
        assertTrue(e.pose.x >= 12f && e.pose.x <= 288f)
    }
    @Test fun workingCompanionHopsOntoVisibleConsoleThenReturns() {
        val e = engine()
        advance(e, 4.5f, working = true)
        assertEquals(210f, e.pose.y, .001f)
        assertEquals(CompanionMood.WORKING, e.pose.mood)
        advance(e, 4.5f, working = true)
        assertEquals(460f, e.pose.y, .001f)
    }
    @Test fun clippedConsoleIsNotAPerch() {
        val e = engine(); e.configure(scene.copy(console = scene.console!!.copy(top = 20f)))
        advance(e, 4.5f, working = true)
        assertEquals(460f, e.pose.y, .001f)
    }
    @Test fun tapFallsOnItsSideThenGetsUp() {
        val e = engine(); e.tap(false)
        advance(e, .4f, roaming = false)
        assertEquals(95f, e.pose.rotation, .001f)
        assertEquals(CompanionMood.SURPRISED, e.pose.mood)
        advance(e, .6f, roaming = false)
        assertEquals(0f, e.pose.rotation, .001f)
        assertEquals(460f, e.pose.y, .001f)
    }
    @Test fun draggingAndThrowingStayInsideWindowAndLandOnNearestPerch() {
        val e = engine(); e.grab(); e.drag(-10000f, -10000f)
        assertEquals(0f, e.pose.x, .001f); assertEquals(0f, e.pose.y, .001f)
        e.drag(10000f, 235f); e.release(Float.MAX_VALUE, false)
        advance(e, 1f, roaming = false)
        assertEquals(280f, e.pose.x, .001f); assertEquals(210f, e.pose.y, .001f)
    }
    @Test fun keyboardResizeRelocatesPetToEditorAndCancelsFlight() {
        val e = engine(); advance(e, 3.9f, working = true)
        e.configure(scene.copy(height = 330f, composer = scene.composer.copy(top = 250f), console = null))
        assertEquals(190f, e.pose.y, .001f); assertEquals(0f, e.pose.lift, .001f)
        advance(e, 1f, working = true, roaming = false)
        assertEquals(190f, e.pose.y, .001f)
    }
    @Test fun reducedMotionNeverStartsAutomaticFlightOrRotation() {
        val e = engine(); val original = e.pose
        advance(e, 12f, working = true, reduced = true)
        assertEquals(original.x, e.pose.x, .001f); assertEquals(original.y, e.pose.y, .001f)
        e.tap(true); e.step(.025f, false, true, true)
        assertEquals(0f, e.pose.rotation, .001f)
        assertEquals(0f, e.pose.lift, .001f)
    }
    @Test fun enablingReducedMotionDuringAJumpLandsImmediately() {
        val e = engine(); advance(e, 3.9f, working = true)
        e.step(0f, true, true, true)
        assertEquals(210f, e.pose.y, .001f)
        assertEquals(0f, e.pose.lift, .001f)
        assertEquals(0f, e.pose.rotation, .001f)
    }
    @Test fun noRoamingStillAllowsPlayAndSleepWakesWhenWorkStarts() {
        val e = engine(); val original = e.pose
        advance(e, 26f, roaming = false)
        assertEquals(original.x, e.pose.x, .001f); assertEquals(CompanionMood.SLEEPY, e.pose.mood)
        e.step(.025f, true, false, false)
        assertEquals(CompanionMood.WORKING, e.pose.mood)
    }
    @Test fun returnFromBackgroundCannotSkipAWholeAnimation() {
        val e = engine(); e.tap(false); e.step(600f, false, false, false)
        assertTrue(e.pose.rotation in 1f..30f)
    }
    @Test fun tinyWindowsHaveNoValidPerch() {
        assertFalse(scene.copy(width = 40f).valid(scene.composer))
        assertFalse(scene.valid(scene.composer.copy(top = 10f)))
        assertFalse(scene.valid(scene.composer.copy(top = 800f)))
    }
}
