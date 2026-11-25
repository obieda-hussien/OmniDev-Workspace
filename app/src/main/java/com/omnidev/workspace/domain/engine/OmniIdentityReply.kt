package com.omnidev.workspace.domain.engine

internal object OmniIdentityReply {
    private val identity = Regex("""(?i)(?:who are you(?: and what do you do)?|(?:انت|أنت) مين(?:\s+و\s*بتعمل (?:اي|ايه))?)[?!؟ .]*""")
    private val product = Regex("""(?i)(?:what is omni dev workspace(?: and what can you do)?|يعني (?:اي|ايه) omni dev workspace(?: و (?:اي|ايه) مميزاتك)?)[?!؟ .]*""")
    private val inventory = Regex("""(?i)(?:list (?:all |your )?(?:available )?tools|what tools do you have|(?:اي|ايه) (?:الادوات|الأدوات) اللي عندك(?: كلها)?|اعمل استكشاف الادوات ده و شوف عندك ادوات اي تاني غير دول)[?!؟ .]*""")
    private val more = Regex("""(?i)(?:what other tools (?:are there|do you have)|و?\s*عندك (?:اي|ايه) تاني غير دول)[?!؟ .]*""")
    private val arabic = Regex("""[\u0600-\u06FF]""")

    fun reply(request: String, previousUserRequest: String?, catalog: RunToolCatalog): String? {
        val input = request.trim()
        val ar = arabic.containsMatchIn(input)
        if (identity.matches(input) || product.matches(input))
            return if (ar) "أنا Omni، مساعد جوه Omni Dev Workspace على أندرويد. بقدر أساعدك في الشات وشغل المشاريع والملفات والبحث والمهام على الجهاز، حسب الأدوات المتاحة وصلاحياتها."
            else "I'm Omni, the assistant in Omni Dev Workspace on Android. I help with chat, projects, files, search, and device workflows according to the tools and permissions available."
        if (!inventory.matches(input) && !(more.matches(input) && previousUserRequest?.trim()?.let(inventory::matches) == true))
            return null
        val tools = catalog.inventory()
        val counts = catalog.inventoryCounts()
        val heading = if (ar) {
            "المسجّل ${counts.registered} أداة، المسموح للجلسة ${counts.authorized}، والمحمّل ${counts.loaded}. جاهزية التشغيل محتاجة تحقق منفصل."
        } else {
            "Registered tools: ${counts.registered}. Authorized for this session: ${counts.authorized}. " +
                "Schemas loaded: ${counts.loaded}. Live backend availability is unknown."
        }
        if (tools.isEmpty()) return heading
        return tools.joinToString("\n", prefix = heading + "\n\n") { tool ->
            "- `${tool.name}` — ${tool.description.trim().lineSequence().firstOrNull().orEmpty().take(140)}"
        }
    }
}
