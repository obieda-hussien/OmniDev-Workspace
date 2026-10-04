package com.omnidev.workspace.data.routines

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Atomic per-record storage. Never persists screenshots, captured text input or tool outputs. */
class RoutineStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    init { directory.mkdirs() }
    @Synchronized fun list(): List<LearnedRoutine> = directory.listFiles().orEmpty()
        .filter { it.name.startsWith("recipe-") && it.extension == "json" }
        .mapNotNull { file -> runCatching {
            require(file.length() <= 1_000_000)
            json.decodeFromString<LearnedRoutine>(file.readText()).also(RoutineValidation::validate)
        }.getOrNull() }.sortedByDescending { it.createdAt }.take(200)
    @Synchronized fun get(id: String): LearnedRoutine? = list().find { it.id == id }
    @Synchronized fun save(routine: LearnedRoutine) {
        RoutineValidation.validate(routine)
        val previous = get(routine.id)
        require(previous != null || list().size < 200) { "Routine limit reached" }
        val edited = previous != null && (previous.steps != routine.steps || previous.triggers != routine.triggers ||
            previous.enabled != routine.enabled || previous.name != routine.name || previous.source != routine.source)
        val versioned = if (edited) routine.copy(revision = maxOf(routine.revision, previous!!.revision + 1)) else
            routine.copy(revision = maxOf(routine.revision, previous?.revision ?: 1))
        write("recipe-${routine.id}.json", json.encodeToString(versioned))
    }
    @Synchronized fun delete(id: String) {
        safeId(id)
        File(directory, "recipe-$id.json").delete()
        runs().filter { it.routineId == id }.forEach { File(directory, "run-${it.id}.json").delete() }
    }
    @Synchronized fun runs(): List<RoutineRun> = directory.listFiles().orEmpty()
        .filter { it.name.startsWith("run-") && it.extension == "json" }
        .mapNotNull { runCatching { require(it.length() <= 1_000_000); json.decodeFromString<RoutineRun>(it.readText()) }.getOrNull() }
    @Synchronized fun saveRun(run: RoutineRun) {
        safeId(run.id); safeId(run.routineId)
        // Parameters are transient: ordinary field contents may still be private.
        write("run-${run.id}.json", json.encodeToString(run.copy(parameters = emptyMap())))
        val finished = runs().filter { it.status in setOf(RoutineRunStatus.COMPLETED, RoutineRunStatus.CANCELLED) }
        finished.sortedByDescending { it.updatedAt }.drop(30).forEach { File(directory, "run-${it.id}.json").delete() }
    }
    private fun safeId(id: String) = require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
    private fun write(name: String, value: String) {
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        java.io.FileOutputStream(temporary).use { stream ->
            stream.write(value.toByteArray(Charsets.UTF_8)); stream.fd.sync()
        }
        check(temporary.renameTo(target)) { "Could not commit routine" }
    }
}
