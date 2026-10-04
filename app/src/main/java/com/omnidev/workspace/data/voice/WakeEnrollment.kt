package com.omnidev.workspace.data.voice

/** Finite enrollment: 5 positives, 2 negatives, one held-out check. Owns all sample buffers. */
class WakeEnrollment : AutoCloseable {
    enum class Phase { EXAMPLES, CONTRAST, VALIDATION, TRAINING_FAILED, VALIDATION_FAILED, COMPLETE }
    private val samples = mutableListOf<WakeFeatures.Sample>()
    private var candidate: PersonalWakeModel? = null
    private var replacementIndex: Int? = null
    var trainingProblem: PersonalWakeModel.TrainingException? = null
        private set
    var phase = Phase.EXAMPLES
        private set
    var validationAttempts = 0
        private set
    val count get() = samples.size
    val nextIndex get() = replacementIndex ?: samples.size
    val canRecord get() = phase in setOf(Phase.EXAMPLES, Phase.CONTRAST, Phase.VALIDATION)
    fun owns(sample: WakeFeatures.Sample) = samples.any { it === sample }

    fun add(sample: WakeFeatures.Sample) {
        check(phase == Phase.EXAMPLES || phase == Phase.CONTRAST)
        val replacement = replacementIndex
        if (replacement == null) samples.add(sample) else {
            wipe(samples[replacement]); samples[replacement] = sample; replacementIndex = null
        }
        phase = if (count < 5) Phase.EXAMPLES else Phase.CONTRAST
        if (count == 7) {
            phase = Phase.TRAINING_FAILED
            trainingProblem = null
            try { candidate = PersonalWakeModel.train(samples.take(5), samples.drop(5)) }
            catch (error: PersonalWakeModel.TrainingException) { trainingProblem = error; throw error }
            phase = Phase.VALIDATION
        }
    }

    fun validate(sample: WakeFeatures.Sample, personalVoice: Boolean): PersonalWakeModel? {
        check(phase == Phase.VALIDATION)
        val model = checkNotNull(candidate)
        return try {
            if (model.match(sample, personalVoice).accepted) model
            else { validationAttempts++; if (validationAttempts >= 3) phase = Phase.VALIDATION_FAILED; null }
        } finally { wipe(sample) }
    }

    fun complete() { close(); phase = Phase.COMPLETE }
    fun retryValidation() { check(phase == Phase.VALIDATION_FAILED); validationAttempts = 0; phase = Phase.VALIDATION }
    fun replaceProblemExample() {
        check(phase == Phase.TRAINING_FAILED)
        val index = checkNotNull(trainingProblem).exampleIndex
        check(index in samples.indices)
        replacementIndex = index; candidate = null; trainingProblem = null; validationAttempts = 0
        phase = if (index < 5) Phase.EXAMPLES else Phase.CONTRAST
    }
    fun redoContrast() {
        candidate = null; replacementIndex = null; trainingProblem = null
        while (samples.size > 5) wipe(samples.removeAt(samples.lastIndex))
        validationAttempts = 0; phase = Phase.CONTRAST
    }
    fun undo() {
        candidate = null; replacementIndex = null; trainingProblem = null
        if (samples.isNotEmpty()) wipe(samples.removeAt(samples.lastIndex))
        validationAttempts = 0
        phase = if (count < 5) Phase.EXAMPLES else Phase.CONTRAST
    }
    fun restart() { close(); phase = Phase.EXAMPLES }
    override fun close() { candidate = null; replacementIndex = null; trainingProblem = null; samples.forEach(::wipe); samples.clear(); validationAttempts = 0 }
    companion object {
        fun wipe(sample: WakeFeatures.Sample) { sample.frames.forEach { it.fill(0f) }; sample.voice.fill(0f) }
    }
}
