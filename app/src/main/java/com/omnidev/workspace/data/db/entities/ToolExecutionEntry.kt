package com.omnidev.workspace.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ToolExecutionEntry — سجل تنفيذ الأداة الدائم
 *
 * يحفظ كل عملية تنفيذ أداة بكافة تفاصيلها لضمان:
 * - الذاكرة الكاملة عبر الجلسات
 * - التعلم من الأخطاء والنجاحات
 * - التشخيص الذاتي والتحسين
 * - الوعي الكامل بتاريخ استخدام الأدوات
 */
@Entity(
    tableName = "tool_execution_log",
    indices = [
        Index(name = "index_tool_log_tool", value = ["toolName"]),
        Index(name = "index_tool_log_session", value = ["sessionId"]),
        Index(name = "index_tool_log_time", value = ["timestamp"])
    ]
)
data class ToolExecutionEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** اسم الأداة المُستخدمة */
    val toolName: String,

    /** المعاملات كـ JSON string */
    @ColumnInfo(defaultValue = "'{}'")
    val parametersJson: String = "{}",

    /** نتيجة التنفيذ (مقتطع لتوفير المساحة) */
    @ColumnInfo(defaultValue = "''")
    val resultSummary: String = "",

    /** هل نجح التنفيذ؟ */
    val success: Boolean,

    /** وقت التنفيذ بالميلي ثانية */
    val executionTimeMs: Long,

    /** حجم النتيجة بالأحرف */
    @ColumnInfo(defaultValue = "0")
    val resultSize: Int = 0,

    /** السياق: ماذا كان الـ Agent يحاول فعله */
    @ColumnInfo(defaultValue = "''")
    val agentContext: String = "",

    /** الأداة السابقة في نفس الجلسة */
    @ColumnInfo(defaultValue = "''")
    val previousToolName: String = "",

    /** رقم الجلسة */
    @ColumnInfo(defaultValue = "''")
    val sessionId: String = "",

    /** وضع التشغيل (DEVELOPER, RESEARCHER, etc.) */
    @ColumnInfo(defaultValue = "''")
    val agentMode: String = "",

    /** رسالة الخطأ إذا فشل التنفيذ */
    @ColumnInfo(defaultValue = "''")
    val errorMessage: String = "",

    /** تقييم جودة النتيجة (0.0 - 1.0) */
    @ColumnInfo(defaultValue = "0.5")
    val resultQuality: Float = 0.5f,

    /** الوقت من اليوم (0-23) */
    @ColumnInfo(defaultValue = "0")
    val hourOfDay: Int = 0,

    /** يوم الأسبوع (1-7) */
    @ColumnInfo(defaultValue = "1")
    val dayOfWeek: Int = 1,

    /** ملاحظات التعلم الذاتي */
    @ColumnInfo(defaultValue = "''")
    val learningNote: String = "",

    /** هل تم وضع علامة للمراجعة؟ */
    @ColumnInfo(defaultValue = "0")
    val flaggedForReview: Boolean = false,

    /** الطابع الزمني */
    val timestamp: Long = System.currentTimeMillis()
)
