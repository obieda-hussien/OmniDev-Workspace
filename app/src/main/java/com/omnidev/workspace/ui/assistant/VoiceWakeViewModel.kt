package com.omnidev.workspace.ui.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omnidev.workspace.data.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Rotation retains training. Only microphone work is cancelled when the Activity pauses. */
internal class VoiceWakeViewModel(application: Application) : AndroidViewModel(application) {
    enum class Operation { NONE, RECORD, TEST, DOWNLOAD, SETTINGS }
    data class State(
        val loaded: Boolean = false,
        val operation: Operation = Operation.NONE,
        val phrase: String = WakePhrasePolicy.DEFAULT,
        val enrolled: Boolean = false,
        val assistantSelected: Boolean = false,
        val listening: Boolean = false,
        val consent: Boolean = false,
        val personal: Boolean = true,
        val locked: Boolean = false,
        val conversation: Boolean = false,
        val count: Int = 0,
        val recordingIndex: Int = 0,
        val trainingIssue: PersonalWakeModel.TrainingIssue? = null,
        val problemExample: Int? = null,
        val phase: WakeEnrollment.Phase = WakeEnrollment.Phase.EXAMPLES,
        val attempts: Int = 0,
        val installed: List<String> = emptyList(),
        val language: String = "en",
        val message: String? = null,
        val error: Boolean = false,
        val progress: String = "",
        val downloadLanguage: String? = null,
    ) { val busy get() = operation != Operation.NONE }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val context get() = getApplication<Application>()
    private val prefs = WakePreferences(application)
    private val store = WakeProfileStore(application)
    private val models = OfflineVoiceModels(application)
    private val enrollment = WakeEnrollment()
    private var job: Job? = null
    private var refreshJob: Job? = null
    private var initialized = false
    init { refresh() }

    fun refresh() {
        if (state.value.busy) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try { snapshot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.update { it.copy(loaded = true) }
                notify(error.message ?: "Could not read voice settings. Check available storage.", true)
            }
        }
    }
    private suspend fun snapshot() {
        val (saved, installed, language) = withContext(Dispatchers.IO) { Triple(store.exists(), models.installedLanguages(), models.language()) }
        if (!initialized) { if (saved) enrollment.complete(); initialized = true }
        mutable.update { it.copy(loaded = true, phrase = prefs.phrase, enrolled = saved,
            assistantSelected = AssistantSettings.isSelected(context), listening = prefs.enabled,
            personal = prefs.personalVoice, locked = prefs.lockScreen, conversation = prefs.autoDictation,
            installed = installed, language = language, count = enrollment.count, phase = enrollment.phase, attempts = enrollment.validationAttempts,
            recordingIndex = enrollment.nextIndex, trainingIssue = enrollment.trainingProblem?.issue, problemExample = enrollment.trainingProblem?.exampleIndex) }
    }
    fun notify(message: String, error: Boolean = false) { mutable.update { it.copy(message = message, error = error) } }
    fun consent(value: Boolean) { mutable.update { it.copy(consent = value) } }
    private fun stopListening() { LocalWakeService.stop(context); LocalVoiceSessionService.stop(context); mutable.update { it.copy(listening = false) } }
    private fun run(operation: Operation, block: suspend () -> Unit) {
        if (state.value.busy || !state.value.loaded) return
        refreshJob?.cancel()
        mutable.update { it.copy(operation = operation, message = null, error = false) }
        job = viewModelScope.launch {
            try { block() }
            catch (timeout: TimeoutCancellationException) { notify("No complete phrase heard. Wait for the recording prompt, say it once, then pause.", true) }
            catch (cancelled: CancellationException) {
                if (operation == Operation.RECORD || operation == Operation.TEST) notify("Recording paused. Your completed examples are retained. Tap Record when ready.")
                throw cancelled
            }
            catch (error: Exception) { notify(error.message ?: "Could not complete this step. Please try again.", true) }
            finally {
                withContext(NonCancellable) {
                    runCatching { snapshot() }.onFailure { notify(it.message ?: "Could not read voice settings.", true) }
                    mutable.update { it.copy(operation = Operation.NONE, progress = "", downloadLanguage = null) }
                }
            }
        }
    }
    fun pauseRecording() { if (state.value.operation in setOf(Operation.RECORD, Operation.TEST)) job?.cancel() }
    fun cancelDownload() { if (state.value.operation == Operation.DOWNLOAD) { notify("Download paused. Tap Download again to resume."); job?.cancel() } }
    fun record() {
        if (!state.value.consent || !enrollment.canRecord) return
        run(Operation.RECORD) {
            stopListening()
            val validating = enrollment.phase == WakeEnrollment.Phase.VALIDATION
            val instruction = if (enrollment.phase == WakeEnrollment.Phase.CONTRAST) "Say the different phrase shown below, then pause." else "Say ‘${prefs.phrase}’ once, then pause."
            mutable.update { it.copy(progress = "Preparing microphone… stay quiet until the recording prompt.") }
            val sample = WakeAudio.sample(context) { mutable.update { it.copy(progress = "Recording · $instruction") } }
            try {
                check(prefs.userCanConfigure()) { "Unlock the phone to continue training." }
                if (!validating) {
                    mutable.update { it.copy(progress = if (enrollment.count >= 6) "Checking your training examples…" else "Saving example…") }
                    withContext(Dispatchers.Default) { enrollment.add(sample) }
                    notify(if (enrollment.phase == WakeEnrollment.Phase.VALIDATION) "Seven examples captured. One final check remains." else "Example saved.")
                } else {
                    mutable.update { it.copy(progress = "Checking the final recording…") }
                    val model = withContext(Dispatchers.Default) { enrollment.validate(sample, state.value.personal) }
                    if (model == null) {
                        notify(if (enrollment.phase == WakeEnrollment.Phase.VALIDATION_FAILED)
                            "Three checks did not match. Training is paused. Try again with the same distance and pronunciation, or replace your training examples."
                        else "This check did not match (${enrollment.validationAttempts}/3). Use the same distance and pronunciation. Your seven training examples are retained.", true)
                    } else {
                        withContext(NonCancellable) {
                            withContext(Dispatchers.IO) { store.save(model) }
                            enrollment.complete()
                        }
                        notify("Wake phrase saved. Setup is complete — start listening when ready.")
                    }
                }
            } finally { if (!enrollment.owns(sample)) WakeEnrollment.wipe(sample) }
        }
    }
    private fun publishEnrollment() { mutable.update { it.copy(phase = enrollment.phase, count = enrollment.count, attempts = enrollment.validationAttempts,
        recordingIndex = enrollment.nextIndex, trainingIssue = enrollment.trainingProblem?.issue, problemExample = enrollment.trainingProblem?.exampleIndex) } }
    fun restart() { if (!state.value.busy) { enrollment.restart(); publishEnrollment(); notify("New training started. Your saved profile stays available until the replacement passes.") } }
    fun redoContrast() { if (!state.value.busy) { enrollment.redoContrast(); publishEnrollment(); notify("Wake examples kept. Record two clearly different phrases.") } }
    fun undo() { if (!state.value.busy) { enrollment.undo(); publishEnrollment(); notify("Last example removed. Record a replacement.") } }
    fun retryValidation() { if (!state.value.busy) { enrollment.retryValidation(); publishEnrollment(); notify("Try the final check again. Your training examples are retained.") } }
    fun replaceProblemExample() {
        if (state.value.busy || enrollment.phase != WakeEnrollment.Phase.TRAINING_FAILED || enrollment.trainingProblem == null) return
        enrollment.replaceProblemExample(); publishEnrollment(); notify("Replace the indicated recording. Your other six examples are retained.")
    }
    fun changePhrase(value: String) = run(Operation.SETTINGS) {
        if (WakePhrasePolicy.normalize(value) == prefs.phrase) return@run
        withContext(Dispatchers.IO) { check(prefs.setPhrase(value)) { "Could not save the phrase." } }
        enrollment.restart(); notify("Phrase changed. Record five examples to train it.")
    }
    fun personal(value: Boolean) { prefs.setPersonalVoice(value); mutable.update { it.copy(personal = value) } }
    fun locked(value: Boolean) { prefs.setLockScreen(value); mutable.update { it.copy(locked = value) } }
    fun conversation(value: Boolean) {
        if (value && state.value.installed.isEmpty()) { notify("Install a speech language below first.", true); return }
        prefs.setAutoDictation(value); mutable.update { it.copy(conversation = value) }
        if (!value) LocalVoiceSessionService.stop(context)
    }
    fun toggleListening() {
        if (state.value.busy) return
        if (prefs.enabled) { stopListening(); notify("Listening stopped.") }
        else {
            val started = LocalWakeService.start(context)
            mutable.update { it.copy(listening = started) }
            if (!started) notify(LocalWakeService.status.value, true)
        }
    }
    fun test() = run(Operation.TEST) {
        stopListening()
        mutable.update { it.copy(progress = "Preparing microphone… stay quiet.") }
        val sample = WakeAudio.sample(context) { mutable.update { it.copy(progress = "Recording test · say ‘${prefs.phrase}’ once, then pause.") } }
        try {
            val match = withContext(Dispatchers.Default) { store.load().match(sample, state.value.personal) }
            notify(if (match.accepted) "Phrase matched. Start listening to use it."
                else if (match.voiceDistance > 2.5f && state.value.personal) "Voice preference did not match. Try the same microphone distance, or turn off Prefer my voice in Listening options."
                else "Phrase did not match. Try the same pronunciation in a quiet room, or retrain.", !match.accepted)
        } finally { WakeEnrollment.wipe(sample) }
    }
    fun testReply() = run(Operation.TEST) {
        stopListening()
        mutable.update { it.copy(progress = "Checking the offline spoken voice…") }
        LocalSpeechOutput(context).use { output ->
            output.initialize()
            check(prefs.userCanConfigure()) { "Unlock the phone to test spoken replies." }
            check(output.say(if (output.language == "ar") "أنا جاهز. الصوت المحلي شغال." else "I'm ready. Offline voice is working.")) {
                "Could not play the offline voice. Check Android text-to-speech settings and media volume."
            }
            notify("Offline spoken reply completed. If you did not hear it, check media volume.")
        }
    }
    fun deleteProfile() = run(Operation.SETTINGS) {
        stopListening(); withContext(Dispatchers.IO) { store.delete() }; enrollment.restart()
        mutable.update { it.copy(consent = false) }; notify("Wake profile deleted. Speech languages are retained.")
    }
    fun install(preset: OfflineVoiceModels.Preset) = run(Operation.DOWNLOAD) {
        stopListening(); mutable.update { it.copy(downloadLanguage = preset.language) }
        var displayedMb = -1L
        models.install(preset, { count ->
            val mb = count / (1024 * 1024)
            if (mb != displayedMb) { displayedMb = mb; mutable.update { it.copy(progress = "Downloading ${preset.name.lowercase()} · $mb MB") } }
        }, { progress -> mutable.update { it.copy(progress = progress) } })
        notify("${preset.name.lowercase().replaceFirstChar { it.uppercase() }} installed and selected. Other languages are retained.")
    }
    fun select(preset: OfflineVoiceModels.Preset) = run(Operation.SETTINGS) {
        LocalVoiceSessionService.stop(context)
        withContext(Dispatchers.IO) { models.select(preset) }; notify("Speech language changed. No download needed.")
    }
    fun deleteLanguage(preset: OfflineVoiceModels.Preset) = run(Operation.SETTINGS) {
        stopListening(); models.delete(preset)
        if (!models.ready()) { prefs.setAutoDictation(false); mutable.update { it.copy(conversation = false) } }
        notify("${preset.name.lowercase().replaceFirstChar { it.uppercase() }} removed. Wake training is retained.")
    }
    override fun onCleared() {
        val pending = job
        pending?.cancel()
        // Do not wipe buffers while a worker is matching, training or encrypting them.
        CoroutineScope(Dispatchers.Default).launch { pending?.join(); enrollment.close() }
        super.onCleared()
    }
}
