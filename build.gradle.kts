
buildscript {
    dependencies {
        // Build/test tooling only. Align Netty modules without packaging them in any APK.
        classpath(platform("io.netty:netty-bom:4.1.137.Final"))
        // Jackson 2.22.0-2.22.2 carry high-severity advisories (GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89,
        // GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54, GHSA-q4xh-88c3-wmh7); 2.22.3 is the first fixed line.
        // AGP/ODC bring Jackson transitively, so align the whole family on a patched release.
        classpath(platform("com.fasterxml.jackson:jackson-bom:2.22.4"))
        classpath("org.bouncycastle:bcprov-jdk18on:1.86")
        classpath("org.bouncycastle:bcpkix-jdk18on:1.86")
        constraints {
            // AGP/UTP and ODC carry their own dependency graphs. App implementation
            // constraints cannot repair these older build-classpath dependencies.
            add("classpath", "com.google.guava:guava:33.7.2-jre") // GHSA-xxph-c9ww-hj94 affects <= 33.7.1
            add("classpath", "com.google.protobuf:protobuf-java:3.25.5")
            add("classpath", "com.google.protobuf:protobuf-kotlin:3.25.5")
            add("classpath", "org.jdom:jdom2:2.0.6.1")
            add("classpath", "commons-beanutils:commons-beanutils:1.11.0")
            add("classpath", "org.apache.commons:commons-lang3:3.21.0")
            add("classpath", "org.bitbucket.b_c:jose4j:0.9.6")
            add("classpath", "org.bouncycastle:bcpg-jdk18on:1.86")
            add("classpath", "org.jsoup:jsoup:1.23.2")
            add("classpath", "org.apache.httpcomponents.client5:httpclient5:5.6.5")
            add("classpath", "org.apache.httpcomponents.client5:httpclient5-cache:5.6.5")
        }
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.owasp.dependencycheck) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// ── Security minimums for EVERY configuration of EVERY project ───────────────────────────────
// The buildscript constraints above only repair the build classpath. AGP also creates internal
// configurations (unified test platform, lint, ...) with their own dependency graphs; GitHub's
// dependency submission resolves them and Dependabot raises alerts on what it finds there
// (io.netty 4.1.93/4.1.110 via grpc-netty, guava <= 33.7.1, protobuf-java 3.24.4).
// Rules only RAISE a version that is below the minimum. They never downgrade, never add a
// dependency, and never touch version ranges, so APK contents are unchanged.
fun versionParts(version: String?): List<Int> =
    Regex("\\d+").findAll(version.orEmpty()).map { it.value.toInt() }.toList()

fun isOlderThan(actual: String, minimum: String): Boolean {
    val a = versionParts(actual)
    val m = versionParts(minimum)
    for (i in 0 until maxOf(a.size, m.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = m.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val requestedVersion = requested.version ?: return@eachDependency
            if (requestedVersion.any { it in "[](),+" }) return@eachDependency // ranges/dynamic: leave alone
            val group = requested.group
            val name = requested.name
            val minimum: String? = when {
                group == "io.netty" && name.startsWith("netty-") &&
                    !name.startsWith("netty-tcnative") && name != "netty-bom" -> "4.1.137.Final"
                group == "com.google.protobuf" &&
                    name in setOf("protobuf-java", "protobuf-kotlin", "protobuf-javalite", "protobuf-java-util") -> "3.25.5"
                group == "com.google.guava" && name == "guava" && requestedVersion.endsWith("-jre") -> "33.7.2-jre"
                group == "com.google.guava" && name == "guava" && requestedVersion.endsWith("-android") -> "33.7.2-android"
                // Guava <= 20.x had a single, suffixless artifact. The -android flavor is the universal
                // replacement: it needs no Java 8 APIs and runs on both Android and a plain JVM.
                group == "com.google.guava" && name == "guava" &&
                    Regex("\\d+(\\.\\d+)*").matches(requestedVersion) -> "33.7.2-android"
                else -> null
            }
            if (minimum != null && isOlderThan(requestedVersion, minimum)) {
                useVersion(minimum)
                because("Security minimum $minimum (known advisories in $requestedVersion)")
            }
        }
    }
}

tasks.register("writeBuildToolDependencyInventory") {
    group = "verification"
    description = "Report the resolved build classpath separately from application runtimes."
    doLast {
        val modules = project.buildscript.configurations.getByName("classpath")
            .incoming.resolutionResult.allComponents.mapNotNull { it.moduleVersion }
            .map { "${it.group}:${it.name}:${it.version}" }.distinct().sorted()
        val report = layout.buildDirectory.file("reports/build-tool-dependencies.txt").get().asFile
        report.parentFile.mkdirs()
        report.writeText(modules.joinToString("\n", postfix = "\n"))
        logger.lifecycle("Build classpath inventory: $report (${modules.size} modules)")
    }
}
