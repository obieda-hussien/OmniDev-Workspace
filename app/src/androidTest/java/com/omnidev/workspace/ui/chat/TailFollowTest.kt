package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TailFollowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readingHistorySurvivesIncomingRowsAndResumeReturnsToTail() {
        val count = mutableStateOf(80)
        lateinit var list: LazyListState
        lateinit var follow: TailFollowState
        compose.setContent {
            list = rememberLazyListState()
            follow = rememberTailFollowState(list, "session", count.value)
            LazyColumn(Modifier.fillMaxSize().testTag("messages"), state = list) {
                items(count.value) { Box(Modifier.height(80.dp)) }
            }
        }
        compose.waitUntil(5_000) { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == 79 }
        compose.runOnIdle { assertEquals(79, list.layoutInfo.visibleItemsInfo.last().index) }

        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        var anchor = 0
        var offset = 0
        compose.runOnIdle {
            assertFalse(follow.following)
            anchor = list.firstVisibleItemIndex
            offset = list.firstVisibleItemScrollOffset
            count.value = 85
        }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(anchor, list.firstVisibleItemIndex)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
            follow.resume()
        }
        compose.waitUntil(5_000) { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == 84 }
        compose.runOnIdle {
            assertTrue(follow.following)
            assertEquals(84, list.layoutInfo.visibleItemsInfo.last().index)
        }
    }

    @Test fun growingReplyKeepsItsActualBottomVisible() {
        val replyHeight = mutableStateOf(120.dp)
        lateinit var list: LazyListState
        compose.setContent {
            list = rememberLazyListState()
            rememberTailFollowState(list, "session", replyHeight.value)
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                items(20) { Box(Modifier.height(80.dp)) }
                item(key = "streaming") { Box(Modifier.height(replyHeight.value)) }
            }
        }
        fun bottomIsVisible(): Boolean {
            val layout = list.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull() ?: return false
            return last.index == 20 && last.offset + last.size <= layout.viewportEndOffset
        }
        compose.waitUntil(5_000) { bottomIsVisible() }
        for (height in listOf(900.dp, 1_800.dp, 2_400.dp)) {
            val expectedHeight = with(compose.density) { height.roundToPx() }
            compose.runOnIdle { replyHeight.value = height }
            compose.waitUntil(5_000) {
                bottomIsVisible() && list.layoutInfo.visibleItemsInfo.last().size == expectedHeight
            }
            compose.runOnIdle {
                val last = list.layoutInfo.visibleItemsInfo.last()
                assertEquals(list.layoutInfo.viewportEndOffset, last.offset + last.size)
            }
        }
    }

    @Test fun populatedSessionAndReopenedConsoleAlignToLatestRow() {
        val count = mutableStateOf(0)
        val enabled = mutableStateOf(false)
        lateinit var list: LazyListState
        compose.setContent {
            list = rememberLazyListState()
            rememberTailFollowState(list, "session", count.value, enabled = enabled.value)
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                items(count.value) { Box(Modifier.height(80.dp)) }
            }
        }
        compose.runOnIdle { count.value = 80 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            enabled.value = true
        }
        compose.waitUntil(5_000) { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == 79 }
    }

}
