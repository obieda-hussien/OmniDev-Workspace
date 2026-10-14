
buildscript {
    dependencies {
        // Build/test tooling only. Align Netty modules without packaging them in any APK.
        classpath(platform("io.netty:netty-bom:4.1.137.Final"))
        classpath("org.bouncycastle:bcprov-jdk18on:1.86")
        classpath("org.bouncycastle:bcpkix-jdk18on:1.86")
        constraints {
            // AGP/UTP and ODC carry their own dependency graphs. App implementation
            // constraints cannot repair these older build-classpath dependencies.
            add("classpath", "com.google.protobuf:protobuf-java:3.25.5")
            add("classpath", "com.google.protobuf:protobuf-kotlin:3.25.5")
            add("classpath", "org.jdom:jdom2:2.0.6.1")
            add("classpath", "commons-beanutils:commons-beanutils:1.11.0")
            add("classpath", "org.apache.commons:commons-lang3:3.18.0")
            add("classpath", "org.bitbucket.b_c:jose4j:0.9.6")
            add("classpath", "org.bouncycastle:bcpg-jdk18on:1.86")
            add("classpath", "org.jsoup:jsoup:1.23.2")
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
