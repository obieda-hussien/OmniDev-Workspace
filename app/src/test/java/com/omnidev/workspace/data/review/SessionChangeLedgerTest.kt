package com.omnidev.workspace.data.review

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionChangeLedgerTest {

    @Test fun diffCountsAddedAndRemovedLines() {
        val d = SessionChangeLedger.buildDiff("a\nb\nc", "a\nB\nc\nd")
        assertEquals(2, d.added)
        assertEquals(1, d.removed)
    }

    @Test fun identicalTextHasNoChangesAndFoldsContext() {
        val d = SessionChangeLedger.buildDiff("x\ny", "x\ny")
        assertEquals(0, d.added + d.removed)
    }

    @Test fun baselineIsCapturedOnceAndRevertRestoresIt() = runBlocking {
        val f = File.createTempFile("ledger", ".txt").apply { writeText("original") }
        val ledger = SessionChangeLedger()
        ledger.recordBeforeWrite(1, f.path, ChangeKind.MODIFIED)
        f.writeText("edit one")
        ledger.recordBeforeWrite(1, f.path, ChangeKind.MODIFIED)
        f.writeText("edit two")
        val c = ledger.forSession(1).single()
        assertEquals(2, c.editCount)
        assertEquals("original", c.baseline)
        ledger.revert(setOf(c.id))
        assertEquals("original", f.readText())
        assertEquals(ChangeStatus.REVERTED, ledger.forSession(1).single().status)
        f.delete()
        Unit
    }

    @Test fun createdThenDeletedLeavesNoEntryAndRevertDeletesCreatedFile() = runBlocking {
        val f = File.createTempFile("ledger", ".txt").also { it.delete() }
        val ledger = SessionChangeLedger()
        ledger.recordBeforeWrite(1, f.path, ChangeKind.CREATED)
        f.writeText("new")
        ledger.revert(ledger.forSession(1).map { it.id }.toSet())
        assertTrue(!f.exists())
        ledger.recordBeforeWrite(2, f.path, ChangeKind.CREATED)
        ledger.recordBeforeWrite(2, f.path, ChangeKind.DELETED)
        assertTrue(ledger.forSession(2).isEmpty())
        Unit
    }

    @Test fun unreadableExistingFileIsNeverDeletedOnRevert() = runBlocking {
        val ghost = File(System.getProperty("java.io.tmpdir"), "ledger-ghost-${System.nanoTime()}.txt")
        val ledger = SessionChangeLedger()
        ledger.recordBeforeWrite(3, ghost.path, ChangeKind.MODIFIED)
        val c = ledger.forSession(3).single()
        assertTrue(!c.baselineKnown)
        ghost.writeText("written later")
        val result = ledger.revert(setOf(c.id))
        assertEquals(0, result.succeeded)
        assertTrue(ghost.exists())
        ghost.delete()
        Unit
    }
}
