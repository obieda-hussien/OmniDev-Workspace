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

    @Test fun uncertainTimeoutPausesCurrentOccurrenceWithRetriesStillAvailable() {
        val taskId = scheduleRetryingTask()
        val previousCallback = TaskSchedulerTool.notificationCallback
        val notifications = mutableListOf<Pair<TaskSchedulerTool.ScheduledTask, TaskSchedulerTool.ExecutionSummary>>()
        TaskSchedulerTool.notificationCallback = { task, summary -> notifications.add(task to summary) }
        try {
            val startedAt = checkNotNull(TaskSchedulerTool.markRunning(taskId))
            val running = checkNotNull(TaskSchedulerTool.getTaskById(taskId))
            val summary = TaskSchedulerTool.ExecutionSummary(
                taskId, running.name, startedAt, startedAt + 60_000,
                0, emptyList(), "", false, errorMessage = "Timeout."
            )

            TaskSchedulerTool.markInterrupted(taskId, startedAt - 1, "Stale attempt.")
            assertEquals(running, TaskSchedulerTool.getTaskById(taskId))
            TaskSchedulerTool.markInterrupted(taskId, startedAt, "Timeout.", summary)

            val paused = checkNotNull(TaskSchedulerTool.getTaskById(taskId))
            assertEquals(TaskSchedulerTool.TaskStatus.PAUSED, paused.status)
            assertEquals(running.occurrenceTimeMillis, paused.occurrenceTimeMillis)
            assertEquals(running.retryCount, paused.retryCount)
            assertEquals(0, paused.runCount)
            assertEquals(listOf(summary), paused.executionSummaries)
            assertEquals(listOf("INTERRUPTED: Timeout."), paused.executionHistory)
            assertEquals(listOf(paused to summary), notifications)
            assertFalse(TaskSchedulerTool.getReadyTasks().any { it.id == taskId })
            assertEquals(paused, TaskSchedulerTool.fromEntity(TaskSchedulerTool.toEntity(paused)))
        } finally {
            TaskSchedulerTool.notificationCallback = previousCallback
            TaskSchedulerTool.deleteTask(taskId)
        }
    }

    @Test fun cancelledTaskStaysCancelledWhenServiceFinishesCancelling() {
        val taskId = scheduleRetryingTask()
        try {
            val startedAt = checkNotNull(TaskSchedulerTool.markRunning(taskId))
            TaskSchedulerTool.cancelTaskById(taskId)
            TaskSchedulerTool.markInterrupted(taskId, startedAt, "Execution interrupted.")
            assertEquals(TaskSchedulerTool.TaskStatus.CANCELLED, TaskSchedulerTool.getTaskById(taskId)?.status)
        } finally {
            TaskSchedulerTool.deleteTask(taskId)
        }
    }

    private fun scheduleRetryingTask(): String {
        val name = "Scheduled replay ${java.util.UUID.randomUUID()}"
        TaskSchedulerTool.scheduleTaskDirectly(name, "Save report", 0, 60, maxRetries = 2)
        return TaskSchedulerTool.getAllTasks().single { it.name == name }.id
    }
    @Test fun cronRejectsZeroAndNegativeSteps() {
        assertFalse(TaskSchedulerTool.isValidCron("*/0 * * * *"))
        assertFalse(TaskSchedulerTool.isValidCron("*/-2 * * * *"))
        assertTrue(TaskSchedulerTool.isValidCron("*/15 * * * *"))
    }

    private val utc = java.util.TimeZone.getTimeZone("UTC")
    private fun at(year: Int, month: Int, day: Int, hour: Int = 9, minute: Int = 0): Long =
        java.util.Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test fun cronRestrictedDaysUseOrAndWildcardDaysUseAnd() {
        // November 13, 2026 was Friday; November 20 Friday and December 13 Sunday.
        assertEquals(at(2026, 11, 20), TaskSchedulerTool.nextCronTime("0 9 13 * 5", at(2026, 11, 13), utc))
        assertEquals(at(2026, 12, 13), TaskSchedulerTool.nextCronTime("0 9 13 * 5", at(2026, 12, 11), utc))
        assertEquals(at(2026, 12, 13), TaskSchedulerTool.nextCronTime("0 9 13 * *", at(2026, 11, 13), utc))
        assertEquals(at(2026, 11, 20), TaskSchedulerTool.nextCronTime("0 9 * * 5", at(2026, 11, 13), utc))
        assertEquals(at(2026, 11, 15), TaskSchedulerTool.nextCronTime("0 9 * * 0", at(2026, 11, 13), utc))
        assertEquals(at(2026, 11, 15), TaskSchedulerTool.nextCronTime("0 9 * * 7", at(2026, 11, 13), utc))
        assertEquals(at(2028, 2, 29), TaskSchedulerTool.nextCronTime("0 9 29 2 *", at(2026, 12, 1), utc))
        assertNull(TaskSchedulerTool.nextCronTime("0 9 31 2 *", at(2026, 1, 1), utc))
    }

    @Test fun finalFailureAdvancesOriginalCadenceWithoutUnlockingDependents() {
        val start = at(2026, 11, 22)
        val running = TaskSchedulerTool.ScheduledTask("id", "Report", "prompt", start,
            recurrenceType = TaskSchedulerTool.RecurrenceType.INTERVAL_MINUTES,
            repeatIntervalMinutes = 60, maxRetries = 1,
            status = TaskSchedulerTool.TaskStatus.RUNNING, occurrenceTimeMillis = start)
        val failure = TaskSchedulerTool.failureSnapshot(running, "network", null, start + 1000)
        val retry = TaskSchedulerTool.afterFailure(failure, start + 1000)
        assertEquals(TaskSchedulerTool.TaskStatus.PENDING, retry.status)
        assertEquals(start + 61000, retry.scheduledTimeMillis)
        assertEquals(start, retry.occurrenceTimeMillis)
        assertEquals(1, retry.retryCount)
        val restored = TaskSchedulerTool.fromEntity(TaskSchedulerTool.toEntity(retry))
        assertEquals(retry, restored)
        val exhausted = TaskSchedulerTool.failureSnapshot(restored.copy(status = TaskSchedulerTool.TaskStatus.RUNNING),
            "timeout", null, start + 120000)
        val next = TaskSchedulerTool.afterFailure(exhausted, start + 120000)
        assertEquals(start + 3600000, next.scheduledTimeMillis)
        assertEquals(TaskSchedulerTool.TaskStatus.PENDING, next.status)
        assertEquals(0, next.retryCount)
        assertEquals(0, next.runCount)
        assertEquals(1, next.failedOccurrenceCount)
        assertEquals("timeout", next.lastResult)
        assertNull(next.occurrenceTimeMillis)
        assertEquals(2, next.executionHistory.size)
        assertEquals(next, TaskSchedulerTool.fromEntity(TaskSchedulerTool.toEntity(next)))
    }

    @Test fun terminalFailureDoesNotResurrectCancelledOnceOrMaxedTasks() {
        val task = TaskSchedulerTool.ScheduledTask("id", "name", "prompt", 1234,
            status = TaskSchedulerTool.TaskStatus.FAILED, maxRuns = 1,
            recurrenceType = TaskSchedulerTool.RecurrenceType.INTERVAL_MINUTES, repeatIntervalMinutes = 5)
        val failed = TaskSchedulerTool.afterFailure(task, 2000)
        assertEquals(TaskSchedulerTool.TaskStatus.FAILED, failed.status)
        assertEquals(1, failed.failedOccurrenceCount)
        val once = task.copy(recurrenceType = TaskSchedulerTool.RecurrenceType.ONCE, maxRuns = null)
        assertEquals(TaskSchedulerTool.TaskStatus.FAILED, TaskSchedulerTool.afterFailure(once, 2000).status)
        val cancelled = task.copy(status = TaskSchedulerTool.TaskStatus.CANCELLED)
        assertEquals(cancelled, TaskSchedulerTool.advanceRecurring(cancelled, 2000))
        val completed = task.copy(status = TaskSchedulerTool.TaskStatus.COMPLETED, runCount = 1)
        assertEquals(completed, TaskSchedulerTool.advanceRecurring(completed, 2000))
    }
}
