package com.omnidev.workspace.data.tools

/**
 * Decides when filesystem operations should bypass the app UID and try Android's
 * privileged shell transport (Shizuku first).
 *
 * This is routing only. It never asserts that shell UID can bypass SELinux,
 * app-data isolation, encryption or read-only mounts.
 */
internal object AndroidProtectedPathPolicy {
    private val shellNamespaces = listOf(
        "/data/",
        "/system/",
        "/system_ext/",
        "/product/",
        "/vendor/",
        "/odm/",
        "/oem/",
        "/apex/",
        "/proc/",
        "/sys/",
        "/dev/",
        "/acct/",
        "/storage/emulated/0/Android/data/",
        "/storage/emulated/0/Android/obb/"
    )

    fun requiresShell(path: String): Boolean {
        val normalized = path.trimEnd('/').ifEmpty { "/" }
        return shellNamespaces.any { prefix ->
            val root = prefix.trimEnd('/')
            normalized == root || normalized.startsWith(prefix)
        }
    }
}
