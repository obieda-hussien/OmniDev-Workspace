package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SystemKnowledgeEntry — معرفة النظام المكتسبة
 *
 * يخزن المعلومات التي يكتشفها الـ Agent عن:
 * - قدرات الأدوات ومتطلباتها
 * - معرفة بيئة النظام والجهاز
 * - الأنماط والأفضليات المكتسبة
 * - التحذيرات والملاحظات المهمة
 */
@Entity(tableName = "system_knowledge")
data class SystemKnowledgeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** نوع المعرفة */
    val knowledgeType: String, // TOOL_CAPABILITY, SYSTEM_INFO, PATTERN, WARNING, PREFERENCE, DEPENDENCY

    /** الموضوع (مثل: اسم الأداة، اسم المكوّن) */
    val subject: String,

    /** المحتوى التفصيلي */
    val content: String,

    /** مستوى الثقة (0.0 - 1.0) */
    val confidence: Float = 1.0f,

    /** عدد مرات التحقق من هذه المعرفة */
    val verificationCount: Int = 1,

    /** هل هذه المعرفة لا تزال صالحة؟ */
    val isValid: Boolean = true,

    /** مصدر المعرفة */
    val source: String = "agent_discovery",

    /** الكلمات المفتاحية للبحث */
    val searchTags: String = "",

    /** أولوية الحقن في System Prompt */
    val injectionPriority: Int = 5, // 1=أعلى, 10=أدنى

    /** الطابع الزمني للإنشاء */
    val createdAt: Long = System.currentTimeMillis(),

    /** آخر تحديث */
    val updatedAt: Long = System.currentTimeMillis()
)
