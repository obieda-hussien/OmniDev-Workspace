package com.omnidev.workspace.data.voice

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceModelFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun model(dir: File, language: String, label: String = "working") {
        File(dir, "am").mkdirs(); File(dir, "conf").mkdirs()
        File(dir, "am/final.mdl").writeText(label)
        File(dir, "conf/model.conf").writeText("config")
        File(dir, "omni-language").writeText(language)
    }
    @Test fun englishAndArabicRemainInstalledIndependently() {
        val files = VoiceModelFiles(temporary.root); files.recover()
        model(files.stage("en"), "en"); files.commit("en")
        model(files.stage("ar"), "ar"); files.commit("ar")
        assertNotNull(files.installed("en")); assertNotNull(files.installed("ar"))
        files.delete("ar")
        assertNotNull(files.installed("en")); assertNull(files.installed("ar"))
    }
    @Test fun existingSingleModelMigratesWithoutRedownload() {
        val legacy = File(temporary.root, "offline-voice-model"); model(legacy, "ar")
        val files = VoiceModelFiles(temporary.root)
        assertEquals(files.directory("ar"), files.installed("ar"))
        assertFalse(legacy.exists())
    }
    @Test fun interruptedReplacementRestoresLastGoodInstall() {
        val files = VoiceModelFiles(temporary.root); files.recover()
        model(files.backup("en"), "en", "previous")
        File(files.directory("en"), "am").mkdirs() // Simulate an incomplete replacement.
        val installed = files.installed("en")!!
        assertEquals("previous", File(installed, "am/final.mdl").readText())
        assertFalse(files.backup("en").exists())
    }
    @Test fun completedReplacementIsKeptAfterProcessDeath() {
        val files = VoiceModelFiles(temporary.root); files.recover()
        model(files.directory("en"), "en", "new"); model(files.backup("en"), "en", "old")
        assertEquals("new", File(files.installed("en"), "am/final.mdl").readText())
        assertFalse(files.backup("en").exists())
    }
    @Test fun invalidStageNeverReplacesAnInstalledModel() {
        val files = VoiceModelFiles(temporary.root); files.recover()
        model(files.directory("en"), "en", "old")
        model(files.stage("en"), "en", "")
        assertThrows(IllegalStateException::class.java) { files.commit("en") }
        assertEquals("old", File(files.installed("en"), "am/final.mdl").readText())
    }
    @Test fun failedDownloadAndPartialStageDoNotAffectReadiness() {
        val files = VoiceModelFiles(temporary.root); files.recover()
        model(files.directory("en"), "en")
        files.archive("ar").writeText("partial download")
        model(files.stage("ar"), "ar") // Staged data is never advertised as installed.
        assertNotNull(files.installed("en")); assertNull(files.installed("ar"))
    }
    @Test fun deletionCannotResurrectAnOlderLegacyCopy() {
        val files = VoiceModelFiles(temporary.root); files.recover()
        model(files.directory("en"), "en", "new")
        model(File(temporary.root, "offline-voice-model"), "en", "old")
        files.delete("en")
        assertNull(files.installed("en"))
    }
}
