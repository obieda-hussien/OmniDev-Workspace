package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
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
@Entity(tableName = "tool_execution_log")
data class ToolExecutionEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** اسم الأداة المُستخدمة */
    val toolName: String,

    /** المعاملات كـ JSON string */
    val parametersJson: String = "{}",

    /** نتيجة التنفيذ (مقتطع لتوفير المساحة) */
    val resultSummary: String = "",

    /** هل نجح التنفيذ؟ */
    val success: Boolean,

    /** وقت التنفيذ بالميلي ثانية */
    val executionTimeMs: Long,

    /** حجم النتيجة بالأحرف */
    val resultSize: Int = 0,

    /** السياق: ماذا كان الـ Agent يحاول فعله */
    val agentContext: String = "",

    /** الأداة السابقة في نفس الجلسة */
    val previousToolName: String = "",

    /** رقم الجلسة */
    val sessionId: String = "",

    /** وضع التشغيل (DEVELOPER, RESEARCHER, etc.) */
    val agentMode: String = "",

    /** رسالة الخطأ إذا فشل التنفيذ */
    val errorMessage: String = "",

    /** تقييم جودة النتيجة (0.0 - 1.0) */
    val resultQuality: Float = 0.5f,

    /** الوقت من اليوم (0-23) */
    val hourOfDay: Int = 0,

    /** يوم الأسبوع (1-7) */
    val dayOfWeek: Int = 1,

    /** ملاحظات التعلم الذاتي */
    val learningNote: String = "",

    /** هل تم وضع علامة للمراجعة؟ */
    val flaggedForReview: Boolean = false,

    /** الطابع الزمني */
    val timestamp: Long = System.currentTimeMillis()
)
