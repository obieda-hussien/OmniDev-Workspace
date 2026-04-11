package com.omnidev.workspace.data.db.dao

import androidx.room.*
import com.omnidev.workspace.data.db.entities.ToolExecutionEntry
import kotlinx.coroutines.flow.Flow

/**
 * ToolExecutionDao — واجهة الوصول لسجل تنفيذ الأدوات
 */
@Dao
interface ToolExecutionDao {

    @Insert
    suspend fun insert(entry: ToolExecutionEntry): Long

    @Update
    suspend fun update(entry: ToolExecutionEntry)

    @Query("SELECT * FROM tool_execution_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<ToolExecutionEntry>

    @Query("SELECT * FROM tool_execution_log WHERE toolName = :toolName ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByTool(toolName: String, limit: Int = 50): List<ToolExecutionEntry>

    @Query("SELECT * FROM tool_execution_log WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getBySession(sessionId: String): List<ToolExecutionEntry>

    @Query("SELECT * FROM tool_execution_log WHERE success = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getFailures(limit: Int = 50): List<ToolExecutionEntry>

    @Query("SELECT * FROM tool_execution_log WHERE flaggedForReview = 1 ORDER BY timestamp DESC")
    suspend fun getFlagged(): List<ToolExecutionEntry>

    // ─── الإحصائيات ──────────────────────────────────────────────────

    @Query("""
        SELECT toolName, 
               COUNT(*) as total, 
               SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) as successes,
               AVG(executionTimeMs) as avgTime,
               MAX(timestamp) as lastUsed
        FROM tool_execution_log 
        GROUP BY toolName 
        ORDER BY total DESC
    """)
    suspend fun getToolStats(): List<ToolUsageStats>

    @Query("SELECT COUNT(*) FROM tool_execution_log")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM tool_execution_log WHERE success = 1")
    suspend fun getSuccessCount(): Int

    @Query("""
        SELECT toolName, COUNT(*) as usageCount 
        FROM tool_execution_log 
        WHERE timestamp > :since
        GROUP BY toolName 
        ORDER BY usageCount DESC 
        LIMIT :limit
    """)
    suspend fun getMostUsedSince(since: Long, limit: Int = 10): List<ToolUsageCount>

    @Query("""
        SELECT toolName, COUNT(*) as usageCount 
        FROM tool_execution_log 
        WHERE previousToolName = :toolName AND success = 1
        GROUP BY toolName 
        ORDER BY usageCount DESC 
        LIMIT :limit
    """)
    suspend fun getToolsUsedAfter(toolName: String, limit: Int = 5): List<ToolUsageCount>

    @Query("SELECT DISTINCT toolName FROM tool_execution_log WHERE success = 0 AND errorMessage != ''")
    suspend fun getProblematicTools(): List<String>

    @Query("SELECT * FROM tool_execution_log WHERE toolName = :toolName AND success = 0 ORDER BY timestamp DESC LIMIT 5")
    suspend fun getRecentFailures(toolName: String): List<ToolExecutionEntry>

    // ─── تنظيف ───────────────────────────────────────────────────────

    @Query("DELETE FROM tool_execution_log WHERE timestamp < :before AND flaggedForReview = 0")
    suspend fun deleteOldEntries(before: Long)

    @Query("DELETE FROM tool_execution_log WHERE id NOT IN (SELECT id FROM tool_execution_log ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun keepOnlyLatest(keepCount: Int = 5000)

    @Query("DELETE FROM tool_execution_log WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ─── Flow للواجهة ────────────────────────────────────────────────

    @Query("SELECT * FROM tool_execution_log ORDER BY timestamp DESC LIMIT 50")
    fun observeRecent(): Flow<List<ToolExecutionEntry>>
}

// ─── Data Classes للإحصائيات ──────────────────────────────────────────

data class ToolUsageStats(
    val toolName: String,
    val total: Int,
    val successes: Int,
    val avgTime: Double,
    val lastUsed: Long
)

data class ToolUsageCount(
    val toolName: String,
    val usageCount: Int
)
