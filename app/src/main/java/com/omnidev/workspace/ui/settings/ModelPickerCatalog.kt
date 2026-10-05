package com.omnidev.workspace.ui.settings

import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.ui.providers.ProviderModelCatalog

/** A successful empty response is authoritative; errors retain the cached catalog. */
internal fun availableModelCatalogs(configured: Set<ModelProvider>, catalogs: Map<ModelProvider, ProviderModelCatalog>,
    registry: Map<ModelProvider, List<AIModel>>, localConfigured: Boolean): Map<ModelProvider, List<AIModel>> {
    val providers = configured + if (localConfigured) setOf(ModelProvider.LOCAL_EDGE) else emptySet()
    return providers.sortedBy { it.displayName }.associateWith { provider ->
        val catalog = catalogs[provider]
        val models = when {
            catalog == null -> registry[provider].orEmpty()
            catalog.models.isNotEmpty() -> catalog.models
            !catalog.isFetching && catalog.error == null && catalog.lastFetchedAt != null -> emptyList()
            else -> registry[provider].orEmpty()
        }
        models.distinctBy { it.id }.sortedBy { it.displayName.lowercase() }
    }
}
