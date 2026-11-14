plugins { id("com.android.library") }

val fixtureDirectory = layout.buildDirectory.dir("generated/neuralFixtures")
val prepareFixtures by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    inputs.files(rootProject.fileTree("neural-core/src/main/java"),
        rootProject.fileTree("neural-core/training"), rootProject.fileTree("neural-core/fixtures"), rootProject.fileTree("neural-core/checkpoints"),
        rootProject.fileTree("neural-core/smoke") {
            include("*AndroidFixtureExporter.java", "AndroidProbeSmoke.java", "SupportRetrievalAndroidSmoke.java")
        },
        fileTree("src/androidTest/java/com/omnidev/workspace/neural/probe") {
            include("*ProbeContract.java")
        },
        rootProject.fileTree("scripts") {
            include("import_clinc_rejection.py", "prepare_bilingual_refusal.py", "prepare_massive_refusal.py",
                "reserve_residual_eval.py", "reserve_utility_eval.py", "prepare_prototype_challenges.py", "reserve_trust_eval.py")
        },
        rootProject.file("scripts/prepare_neural_android_fixtures.sh"),
        rootProject.file("scripts/prepare_support_android.sh"),
        rootProject.file("scripts/prepare_product_support.py"), rootProject.file("scripts/prepare_ranked_support.py"),
        rootProject.file("scripts/build_product_knowledge.py"),
        rootProject.file("knowledge/omni-product.jsonl"), rootProject.file("docs/OMNI_PRODUCT_KNOWLEDGE.md"))
    outputs.dir(fixtureDirectory)
    commandLine("bash", "scripts/prepare_neural_android_fixtures.sh", fixtureDirectory.get().asFile.absolutePath)
}

android {
    namespace = "com.omnidev.workspace.neural.android"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        targetSdk = 36
        testApplicationId = "com.omnidev.workspace.neuralprobe"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    sourceSets.getByName("androidTest") {
        java.srcDir(fixtureDirectory.map { it.dir("java") })
        assets.srcDir(fixtureDirectory.map { it.dir("assets") })
    }
}
tasks.matching { it.name == "preDebugAndroidTestBuild" }.configureEach { dependsOn(prepareFixtures) }

dependencies {
    androidTestImplementation(project(":neural-core"))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
