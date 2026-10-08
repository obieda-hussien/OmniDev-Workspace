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
        assertTrue(e.pose.x in 0f..300f)
        assertTrue(e.perchId in listOf("console", "composer"))
        assertTrue(e.pose.y == 210f || e.pose.y == 460f)
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
        e.lookAt(0f, 0f, wake = true); e.step(.025f, false, false, false)
        assertEquals(CompanionMood.AWAKE, e.pose.mood)
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

    @Test fun scrollingFollowsTheSameConsoleWithoutTeleportingToComposer() {
        val e = engine(); advance(e, 4.5f, working = true)
        e.configure(scene.copy(console = scene.console!!.copy(top = 255f)))
        assertEquals("console", e.perchId)
        assertEquals(195f, e.pose.y, .001f)
        assertFalse(e.isAirborne)
    }
    @Test fun scrollingNearTopEscapesBeforeTheBodyIsClipped() {
        val e = engine(); advance(e, 4.5f, working = true)
        e.configure(scene.copy(console = scene.console!!.copy(top = 75f)))
        assertTrue(e.isAirborne)
        assertTrue(e.pose.y >= 0f)
        advance(e, 1f, roaming = false)
        assertEquals("composer", e.perchId)
        assertEquals(460f, e.pose.y, .001f)
    }
    @Test fun scrollingNearBottomCanEscapeOntoAUserMessage() {
        val message = CompanionPerch(40f, 330f, 440f, "message-user")
        val updated = scene.copy(messages = listOf(message), viewportBottom = 470f)
        val e = CompanionMotion().apply { configure(updated) }
        advance(e, 4.5f, working = true)
        e.configure(updated.copy(console = scene.console!!.copy(top = 465f)))
        assertTrue(e.isAirborne)
        advance(e, 1f, roaming = false)
        assertEquals("message-user", e.perchId)
        assertEquals(380f, e.pose.y, .001f)
    }
    @Test fun unrelatedMessageLayoutDoesNotResetFlightOrIdleTimer() {
        val e = engine(); advance(e, 3.9f, working = true)
        val before = e.pose
        e.configure(scene.copy(messages = listOf(CompanionPerch(40f, 300f, 390f, "message-user"))))
        assertEquals(before.x, e.pose.x, .001f); assertEquals(before.y, e.pose.y, .001f)
        advance(e, .8f, working = true, roaming = false)
        assertEquals("console", e.perchId)
        advance(e, 3f, working = true)
        repeat(10) { e.configure(scene.copy(messages = listOf(CompanionPerch(40f, 300f, 390f + it, "message-user")))) }
        advance(e, .6f, working = true)
        assertTrue(e.isAirborne)
    }
    @Test fun removedLazyItemStartsAnEscapeFromCurrentPosition() {
        val message = CompanionPerch(40f, 330f, 340f, "message-user")
        val e = CompanionMotion().apply { configure(scene.copy(messages = listOf(message))) }
        e.grab(); e.drag(-100f, -180f); e.release(0f, false)
        advance(e, 1f, roaming = false)
        assertEquals("message-user", e.perchId)
        val oldY = e.pose.y
        e.configure(scene)
        assertTrue(e.isAirborne); assertEquals(oldY, e.pose.y, .001f)
        advance(e, 1f, roaming = false)
        assertEquals("console", e.perchId)
    }
    @Test fun movingDestinationIsTrackedUntilLanding() {
        val message = CompanionPerch(40f, 330f, 340f, "message-user")
        val e = CompanionMotion().apply { configure(scene.copy(messages = listOf(message))) }
        e.grab(); e.drag(-100f, -180f); e.release(0f, false)
        advance(e, .3f, roaming = false)
        e.configure(scene.copy(messages = listOf(message.copy(top = 355f))))
        advance(e, .6f, roaming = false)
        assertEquals("message-user", e.perchId)
        assertEquals(295f, e.pose.y, .001f)
    }
    @Test fun shortUserBubbleCanSupportTheBodyWithSafeOverhang() {
        val short = CompanionPerch(150f, 182f, 300f, "message-hi")
        assertTrue(scene.valid(short))
        assertEquals(136f, scene.x(900f, short), .001f)
    }
    @Test fun reducedMotionEscapeImmediatelyFindsAVisiblePerch() {
        val e = engine(); advance(e, 4.5f, working = true)
        e.configure(scene.copy(console = scene.console!!.copy(top = 20f)), reduced = true)
        assertEquals("composer", e.perchId); assertFalse(e.isAirborne)
        assertEquals(460f, e.pose.y, .001f)
    }
    @Test fun throwingBouncesOffWallsAndEventuallyLandsWithoutCoveringEditor() {
        val e = engine(); e.grab(); e.drag(0f, -260f); e.release(2000f, false, -500f)
        var movedLeft = false
        var previousX = e.pose.x
        repeat(120) {
            e.step(.025f, false, false, false)
            movedLeft = movedLeft || e.pose.x < previousX
            previousX = e.pose.x
            assertTrue(e.pose.x in 0f..300f); assertTrue(e.pose.y in 0f..460f)
        }
        assertTrue(movedLeft); assertNotNull(e.perchId); assertFalse(e.isAirborne)
    }
    @Test fun eyesFollowThePointerThenReturnToNeutral() {
        val e = engine(); e.lookAt(1000f, -1000f)
        advance(e, .4f, roaming = false)
        assertTrue(e.pose.lookX > .4f); assertTrue(e.pose.lookY < -.7f)
        advance(e, 3f, roaming = false)
        assertTrue(kotlin.math.abs(e.pose.lookX) < .01f)
    }
    @Test fun expressionsUseRealActivityAndOnlySuccessfulRunsCelebrate() {
        val e = engine()
        e.step(.025f, true, false, false, CompanionActivity.TOOL)
        assertEquals(CompanionMood.FOCUSED, e.pose.mood)
        e.step(.025f, true, false, false, CompanionActivity.WAITING)
        assertEquals(CompanionMood.WAITING, e.pose.mood)
        e.step(.025f, false, false, false, CompanionActivity.SUCCESS)
        assertEquals(CompanionMood.HAPPY, e.pose.mood)
        assertTrue(e.pose.sparkle > 0f)
        advance(e, 2f, roaming = false)
        assertEquals(CompanionMood.AWAKE, e.pose.mood)
        e.step(.025f, true, false, false, CompanionActivity.THINKING)
        e.step(.025f, true, false, false, CompanionActivity.ERROR)
        assertEquals(CompanionMood.CONCERNED, e.pose.mood)
        e.step(.025f, false, false, false, CompanionActivity.SUCCESS)
        assertEquals(0f, e.pose.sparkle, .001f)
    }
    @Test fun openingExistingCompletedConversationDoesNotCelebrate() {
        val e = engine(); e.step(.025f, false, true, false, CompanionActivity.SUCCESS)
        assertEquals(0f, e.pose.sparkle, .001f); assertFalse(e.isAirborne)
        e.step(.025f, false, false, false, CompanionActivity.LISTENING)
        assertEquals(CompanionMood.LISTENING, e.pose.mood)
    }

    @Test fun distantCaretMovementChangesTheViewingAngleInsteadOfSaturating() {
        val e = engine()
        e.lookAt(0f, 650f); advance(e, .3f, roaming = false)
        val left = e.pose.lookX
        e.lookAt(220f, 650f); advance(e, .3f, roaming = false)
        assertTrue(e.pose.lookX > left + .3f)
        assertTrue(e.pose.lookY > 0f)
        e.lookAt(360f, 650f); advance(e, .3f, roaming = false)
        assertTrue(e.pose.lookX > 0f)
    }

    @Test fun idleBreathingAndCuriousTiltStaySubtleAndReducedMotionDisablesThem() {
        val e = engine(); advance(e, 2.5f, roaming = false)
        assertTrue(e.pose.stretch in .97f..1.03f)
        assertTrue(kotlin.math.abs(e.pose.bodyTilt) in .1f..2.1f)
        e.step(.025f, false, false, true)
        assertEquals(1f, e.pose.stretch, .001f)
        assertEquals(0f, e.pose.bodyTilt, .001f)
        assertEquals(0f, e.pose.earTilt, .001f)
    }

    @Test fun landingCompressesThenReboundsBeforeSettling() {
        val e = engine(); e.grab(); e.drag(-120f, -200f); e.release(0f, false)
        var compressed = false
        var rebounded = false
        repeat(100) {
            e.step(.01f, false, false, false)
            if (e.perchId != null) {
                compressed = compressed || e.pose.stretch < .9f
                rebounded = rebounded || e.pose.stretch > 1.04f
            }
        }
        assertTrue(compressed); assertTrue(rebounded)
        assertTrue(e.pose.stretch in .97f..1.03f)
    }
}
