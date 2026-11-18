plugins {
    id("java-library")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.withType<JavaCompile>().configureEach {
    // Runs on Android API 24+ without requiring Android framework classes.
    options.release.set(8)
    options.encoding = "UTF-8"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

// Package only the two fitted decision heads and their matching vocabulary.
val bundledDecisionSources by tasks.registering(Sync::class) {
    val checkpoints = layout.projectDirectory.dir("checkpoints")
    from(checkpoints.dir("public-decision-v1")) { include("TrainedPublicDecision.java") }
    from(checkpoints.dir("public-refusal-v1")) { include("TrainedPublicRefusal.java") }
    from(checkpoints.dir("rich-text-v1")) { include("TrainedRichVocabulary.java") }
    into(layout.buildDirectory.dir("generated/bundledDecision"))
    filteringCharset = "UTF-8"
    filter { line: String ->
        if (line.startsWith("import ")) "package com.omnidev.workspace.domain.neural.bundled;\n$line" else line
    }
}

sourceSets.main {
    java.srcDir(bundledDecisionSources)
}

tasks.processResources {
    for (dataset in listOf("massive-refusal", "clinc-subset")) {
        from("fixtures/$dataset") {
            include("LICENSE", "NOTICE.md", "provenance.json")
            into("omni-model-attribution/$dataset")
        }
    }
}
