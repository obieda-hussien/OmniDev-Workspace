package com.omnidev.workspace.data.tools

/** Fixed own-app commands. Accessibility appends our service without replacing other services. */
internal object PrivilegedAccessSetupPlan {
    val keys = listOf("accessibility", "notification_listener", "notification_policy", "battery_optimization")
    fun command(key: String, packageName: String, userId: Int, component: String? = null): String {
        require(userId >= 0 && packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))
        if (key in setOf("accessibility", "notification_listener")) {
            require(component != null && component.startsWith("$packageName/") &&
                component.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+")))
        }
        return when (key) {
            "accessibility" -> """
                omni_services=${'$'}(settings --user $userId get secure enabled_accessibility_services) || exit 1
                [ "${'$'}omni_services" = "null" ] && omni_services=''
                case ":${'$'}omni_services:" in
                    *':$component:'*) ;;
                    *) settings --user $userId put secure enabled_accessibility_services "${'$'}{omni_services:+${'$'}omni_services:}$component" || exit 1 ;;
                esac
                settings --user $userId put secure accessibility_enabled 1
            """.trimIndent()
            "notification_listener" -> "cmd notification allow_listener '$component' $userId"
            "notification_policy" -> "cmd notification allow_dnd '$packageName' $userId"
            "battery_optimization" -> "cmd deviceidle whitelist '+$packageName'"
            else -> error("Unsupported automatic special access")
        }
    }
}
