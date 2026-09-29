package com.omnidev.workspace.data.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramOwnerLinkStoreTest {
    @Test
    fun onlyMatchingPrivateUserAndChatCanBecomeOwner() {
        assertTrue(TelegramOwnerLinkStore.isPrivateOwnerChat(123L, 123L, "private"))
        assertFalse(TelegramOwnerLinkStore.isPrivateOwnerChat(123L, 456L, "private"))
        assertFalse(TelegramOwnerLinkStore.isPrivateOwnerChat(-123L, 456L, "group"))
        assertFalse(TelegramOwnerLinkStore.isPrivateOwnerChat(-123L, 456L, "supergroup"))
        assertFalse(TelegramOwnerLinkStore.isPrivateOwnerChat(123L, 123L, "channel"))
        assertFalse(TelegramOwnerLinkStore.isPrivateOwnerChat(0L, 0L, "private"))
    }
}
