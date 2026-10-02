package com.omnidev.workspace.ui.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenSelectionTest {
    @Test fun portraitDragUsesSourcePixels() {
        assertEquals(ScreenSelection.Crop(100, 200, 300, 400),
            ScreenSelection.crop(720, 1280, 360f, 640f, 50f, 100f, 200f, 300f))
    }
    @Test fun reversedDragIsIdenticalIncludingOnArabicDevices() {
        assertEquals(ScreenSelection.crop(720, 1280, 360f, 640f, 50f, 100f, 200f, 300f),
            ScreenSelection.crop(720, 1280, 360f, 640f, 200f, 300f, 50f, 100f))
    }
    @Test fun landscapePreviewAccountsForHorizontalLetterboxing() {
        assertEquals(ScreenSelection.Crop(0, 0, 720, 1280),
            ScreenSelection.crop(720, 1280, 640f, 360f, 0f, 0f, 640f, 360f))
        assertEquals(ScreenSelection.Crop(0, 0, 360, 640),
            ScreenSelection.crop(720, 1280, 640f, 360f, 218.75f, 0f, 320f, 180f))
    }
    @Test fun wideScreenAccountsForVerticalLetterboxing() {
        assertEquals(ScreenSelection.Crop(0, 0, 640, 360),
            ScreenSelection.crop(1280, 720, 360f, 640f, 0f, 218.75f, 180f, 320f))
    }
    @Test fun offScreenDragClampsToImage() {
        assertEquals(ScreenSelection.Crop(0, 0, 720, 1280),
            ScreenSelection.crop(720, 1280, 360f, 640f, -100f, -100f, 1000f, 1000f))
    }
    @Test fun tapAndLetterboxOnlySelectionsAreRejected() {
        assertNull(ScreenSelection.crop(720, 1280, 360f, 640f, 50f, 100f, 50.5f, 100.5f))
        assertNull(ScreenSelection.crop(720, 1280, 640f, 360f, 0f, 0f, 100f, 100f))
    }
    @Test fun invalidViewportOrCoordinatesAreRejected() {
        assertNull(ScreenSelection.crop(720, 1280, 0f, 640f, 0f, 0f, 100f, 100f))
        assertNull(ScreenSelection.crop(720, 1280, 360f, 640f, Float.NaN, 0f, 100f, 100f))
        assertNull(ScreenSelection.crop(0, 1280, 360f, 640f, 0f, 0f, 100f, 100f))
    }
}
