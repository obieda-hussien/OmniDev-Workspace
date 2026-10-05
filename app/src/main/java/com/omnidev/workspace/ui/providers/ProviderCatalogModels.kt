package com.omnidev.workspace.ui.providers

import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider

/** Use the same identity in the registry, picker and persisted role assignment. */
internal fun normalizeCatalogModels(provider: ModelProvider, models: List<AIModel>): List<AIModel> =
    models.map { model ->
        model.copy(id = when {
            provider == ModelProvider.GITHUB_COPILOT -> model.id
            "::" in model.id -> model.id
            else -> "${provider.name}::${model.id}"
        })
    }.distinctBy { it.id }
