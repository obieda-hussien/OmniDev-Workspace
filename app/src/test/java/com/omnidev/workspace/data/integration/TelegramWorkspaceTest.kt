package com.omnidev.workspace.data.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramWorkspaceTest {
    @Test fun acceptsSpecificLocalFolders() {
        assertEquals("/storage/emulated/0/Documents/Project One", TelegramWorkspace.folderPath("primary:Documents/Project One"))
        assertEquals("/storage/1234-5678/Workspace", TelegramWorkspace.folderPath("1234-5678:Workspace"))
    }

    @Test fun refusesStorageRootsAndNonSpecificPaths() {
        for (id in listOf("primary:", "primary", "primary:../Documents", "primary:/Documents", "primary:Documents//Project", "../other:Documents")) {
            assertNull(id, TelegramWorkspace.folderPath(id))
        }
    }

    @Test fun directsBothModesToTelegramSettings() {
        for (mode in listOf("agent", "swarm")) {
            val text = TelegramWorkspace.missingScope(mode)
            assertTrue(text.contains("Linked accounts → Telegram → Project folder"))
            assertTrue(text.contains("/mode $mode"))
        }
    }
}
