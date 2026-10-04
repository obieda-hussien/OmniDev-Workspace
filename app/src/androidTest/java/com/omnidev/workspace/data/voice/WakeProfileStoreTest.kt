package com.omnidev.workspace.data.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class WakeProfileStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = WakeProfileStore(context)
    private val file = File(context.noBackupFilesDir, "personal-wake-model.v1")
    private fun sample(length: Int = 60, frequency: Int = 1) = WakeFeatures.Sample(
        Array(length) { n -> FloatArray(24) { c -> sin(2 * PI * frequency * n / length + c * .3).toFloat() } }, FloatArray(12))
    private fun model() = PersonalWakeModel.train(List(5) { sample(58 + it) }, listOf(sample(frequency = 3), sample(frequency = 4)))
    @Before fun before() { store.delete() }
    @After fun after() { store.delete() }
    @Test fun encryptedProfileSurvivesReloadAndDeletionRemovesIt() {
        val model = model()
        store.save(model)
        assertTrue(store.exists())
        assertFalse(file.readBytes().contentEquals(model.encode()))
        assertTrue(store.load().match(sample(65)).accepted)
        store.delete()
        assertFalse(store.exists())
        assertThrows(Exception::class.java) { store.load() }
    }
    @Test fun tamperingCannotProduceAUsableModel() {
        store.save(model())
        file.writeBytes(file.readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() })
        assertThrows(Exception::class.java) { store.load() }
    }
}
