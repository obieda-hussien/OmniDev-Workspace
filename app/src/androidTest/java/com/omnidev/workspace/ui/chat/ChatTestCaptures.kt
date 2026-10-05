package com.omnidev.workspace.ui.chat

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue

internal fun saveChatPreview(compose: ComposeContentTestRule, tag: String, name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = checkNotNull(context.getExternalFilesDir("chat-previews"))
    check(directory.isDirectory || directory.mkdirs())
    val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
    File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
}
