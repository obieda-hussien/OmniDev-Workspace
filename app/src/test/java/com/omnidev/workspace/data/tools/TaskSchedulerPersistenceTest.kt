package com.omnidev.workspace.data.tools

import org.junit.Assert.*
import org.junit.Test

class TaskSchedulerPersistenceTest {
    @Test fun roundTripPreservesRecurrenceDependenciesAndHistory() {
        val task = TaskSchedulerTool.ScheduledTask(id = "stable", name = "Backup", prompt = "Save report",
            scheduledTimeMillis = 1234, recurrenceType = TaskSchedulerTool.RecurrenceType.DAILY,
            timeOfDay = "09:30", dependsOn = listOf("prepare"), tags = listOf("work"),
            status = TaskSchedulerTool.TaskStatus.PENDING, runCount = 3,
            executionHistory = listOf("last result"), maxRetries = 2, timeoutMinutes = 5)
        assertEquals(task, TaskSchedulerTool.fromEntity(TaskSchedulerTool.toEntity(task)))
    }
    @Test fun interruptedRunsArePausedRatherThanRepeated() {
        val task = TaskSchedulerTool.ScheduledTask("id", "name", "prompt", 1234,
            status = TaskSchedulerTool.TaskStatus.RUNNING)
        val restored = TaskSchedulerTool.fromEntity(TaskSchedulerTool.toEntity(task))
        assertEquals(TaskSchedulerTool.TaskStatus.PAUSED, restored.status)
        assertEquals(task.id, restored.id)
    }
    @Test fun cronRejectsZeroAndNegativeSteps() {
        assertFalse(TaskSchedulerTool.isValidCron("*/0 * * * *"))
        assertFalse(TaskSchedulerTool.isValidCron("*/-2 * * * *"))
        assertTrue(TaskSchedulerTool.isValidCron("*/15 * * * *"))
    }
}
