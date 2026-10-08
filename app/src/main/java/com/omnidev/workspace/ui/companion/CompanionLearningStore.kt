package com.omnidev.workspace.ui.companion

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** One application-wide identity across chats and the assistant; all training/disk IO is off UI. */
internal class CompanionLearningStore private constructor(context: Context) {
    private sealed interface Command {
        data class Learn(val epoch: Int, val decision: CompanionDecision, val reward: Float) : Command
        data class Feel(val epoch: Int, val feeling: CompanionFeeling) : Command
        data class Save(val epoch: Int) : Command
        data object Reset : Command
    }
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "companion-mind-v1.bin"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(128)
    private val policy = AtomicReference(CompanionPolicy())
    private val affect = AtomicReference(CompanionFeeling() to 0L)
    private val generation = AtomicInteger(0)
    val ready = MutableStateFlow(false)
    val epoch = MutableStateFlow(0)
    val observations = MutableStateFlow(0)
    val saveFailed = MutableStateFlow(false)
    init {
        scope.launch {
            var learner = runCatching {
                file.openRead().use { input ->
                    val buffer = ByteArray(CompanionLearner.MAX_BYTES + 1)
                    var count = 0
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        if (read == 0) break
                        count += read
                    }
                    CompanionLearner.decode(buffer.copyOf(count))
                }
            }.getOrNull() ?: CompanionLearner()
            fun publish() {
                policy.set(learner.policy()); affect.set(learner.feeling to learner.savedAt)
                observations.value = learner.observations
            }
            publish(); ready.value = true
            var dirty = false
            var saveScheduled = false
            fun scheduleSave() {
                if (saveScheduled) return
                saveScheduled = true
                val currentEpoch = generation.get()
                scope.launch { delay(750); commands.send(Command.Save(currentEpoch)) }
            }
            for (command in commands) {
                when (command) {
                    is Command.Learn -> if (command.epoch == generation.get()) {
                        learner.learn(command.decision, command.reward)
                        policy.set(learner.policy()); observations.value = learner.observations
                        dirty = true; scheduleSave()
                    }
                    is Command.Feel -> if (command.epoch == generation.get()) {
                        learner.feeling = command.feeling; learner.savedAt = System.currentTimeMillis().coerceAtLeast(0)
                        dirty = true; scheduleSave()
                    }
                    Command.Reset -> {
                        // Ignore queued callbacks from old sessions after reset.
                        val nextEpoch = generation.incrementAndGet()
                        learner = CompanionLearner(); learner.savedAt = System.currentTimeMillis().coerceAtLeast(0)
                        file.delete()
                        publish(); epoch.value = nextEpoch
                        dirty = true; saveScheduled = false; scheduleSave()
                    }
                    is Command.Save -> if (command.epoch == generation.get()) {
                        saveScheduled = false
                        if (dirty) {
                            var output: java.io.FileOutputStream? = null
                            try {
                                output = file.startWrite(); output.write(learner.encode()); file.finishWrite(output)
                                dirty = false; saveFailed.value = false
                            } catch (error: Exception) {
                                output?.let(file::failWrite); saveFailed.value = true
                                Log.w("OmniCompanion", "Could not save companion habits", error)
                            }
                        }
                    }
                }
            }
        }
    }
    fun session(): CompanionMind {
        val sessionEpoch = generation.get()
        return CompanionMind(currentFeeling(), policy = { policy.get() },
            learn = { decision, reward -> commands.trySend(Command.Learn(sessionEpoch, decision, reward)) },
            remember = { value -> if (ready.value && sessionEpoch == generation.get()) {
                affect.set(value to System.currentTimeMillis().coerceAtLeast(0))
                commands.trySend(Command.Feel(sessionEpoch, value))
            } })
    }
    fun currentFeeling(): CompanionFeeling {
        val (feeling, timestamp) = affect.get()
        val elapsed = if (timestamp == 0L) 0f else ((System.currentTimeMillis() - timestamp).coerceAtLeast(0) / 1000f)
        return feeling.afterAbsence(elapsed)
    }
    fun reset() { commands.trySend(Command.Reset) }
    companion object {
        @Volatile private var instance: CompanionLearningStore? = null
        fun get(context: Context): CompanionLearningStore = instance ?: synchronized(this) {
            instance ?: CompanionLearningStore(context).also { instance = it }
        }
    }
}
