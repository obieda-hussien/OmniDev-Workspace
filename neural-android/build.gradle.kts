plugins { id("com.android.library") }

val fixtureDirectory = layout.buildDirectory.dir("generated/neuralFixtures")
val prepareFixtures by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    inputs.files(rootProject.fileTree("neural-core/src/main/java"),
        rootProject.fileTree("neural-core/training"), rootProject.fileTree("neural-core/fixtures"), rootProject.fileTree("neural-core/checkpoints"),
        rootProject.file("neural-core/smoke/AndroidFixtureExporter.java"),
        rootProject.file("neural-core/smoke/AndroidProbeSmoke.java"),
        file("src/androidTest/java/com/omnidev/workspace/neural/probe/NeuralProbeContract.java"),
        rootProject.file("scripts/prepare_neural_android_fixtures.sh"),
        rootProject.file("scripts/import_clinc_rejection.py"))
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
