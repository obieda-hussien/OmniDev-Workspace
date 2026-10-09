package com.omnidev.workspace.data.chatmedia

internal object ProfileReferencePolicy {
    fun validate(kind: String, provider: String, model: String) {
        require(kind == "image") { "Profile references currently support image requests only." }
        require(provider == "gemini" && model.startsWith("gemini-") && model.contains("image") ||
            provider == "openai" && model.startsWith("gpt-image-")) {
            "The selected model cannot use profile references here. Select a Gemini image or OpenAI GPT Image model, or explicitly request an image without references."
        }
    }
    fun validFile(value: String) = value.matches(Regex("(?:face|body)-[a-f0-9-]{36}\\.jpg"))
    fun validateSelection(selection: List<String>, allowed: Boolean, current: Set<String>) {
        require(allowed) { "Enable reference use in Your profile before generating with your photos." }
        require(selection.isNotEmpty()) { "Add a face or full-body reference photo in Your profile first." }
        require(selection.size in 1..2 && selection.distinct().size == selection.size &&
            selection.all { validFile(it) && it in current }) {
            "Reference photos were removed or replaced. Review Your profile and send a new request."
        }
    }
}
