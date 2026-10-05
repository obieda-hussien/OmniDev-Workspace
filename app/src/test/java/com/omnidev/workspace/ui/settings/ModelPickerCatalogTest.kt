package com.omnidev.workspace.ui.settings

import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.ui.providers.ProviderModelCatalog
import org.junit.Assert.*
import org.junit.Test

class ModelPickerCatalogTest {
    private val remote = AIModel("OPENAI::example", "Example", ModelProvider.OPENAI, contextWindow = 128_000)
    private val local = remote.copy(id = "local-edge-model", provider = ModelProvider.LOCAL_EDGE)
    private val registry = mapOf(ModelProvider.OPENAI to listOf(remote), ModelProvider.LOCAL_EDGE to listOf(local),
        ModelProvider.GITHUB_COPILOT to listOf(remote.copy(id = "copilot/example", provider = ModelProvider.GITHUB_COPILOT)))

    @Test fun disconnectedProvidersAndUnconfiguredLocalModelsAreNotOffered() {
        assertEquals(setOf(ModelProvider.OPENAI), availableModelCatalogs(setOf(ModelProvider.OPENAI), emptyMap(), registry, false).keys)
        assertTrue(availableModelCatalogs(emptySet(), emptyMap(), registry, false).isEmpty())
        assertEquals(listOf(local), availableModelCatalogs(emptySet(), emptyMap(), registry, true)[ModelProvider.LOCAL_EDGE])
    }
    @Test fun authoritativeEmptyResponseDoesNotResurrectUnavailableModels() {
        val catalog = mapOf(ModelProvider.OPENAI to ProviderModelCatalog(lastFetchedAt = 123L))
        assertTrue(availableModelCatalogs(setOf(ModelProvider.OPENAI), catalog, registry, false).getValue(ModelProvider.OPENAI).isEmpty())
    }
    @Test fun failedRefreshKeepsTheLastKnownCatalogAndStableIds() {
        val cached = remote.copy(id = "OPENAI::cached", displayName = "Cached")
        val catalog = mapOf(ModelProvider.OPENAI to ProviderModelCatalog(models = listOf(cached, cached), error = "Offline", lastFetchedAt = 123L))
        assertEquals(listOf(cached), availableModelCatalogs(setOf(ModelProvider.OPENAI), catalog, registry, false)[ModelProvider.OPENAI])
    }
    @Test fun firstFetchShowsRegisteredCacheUntilTheRequestCompletes() {
        val loading = mapOf(ModelProvider.OPENAI to ProviderModelCatalog(isFetching = true))
        assertEquals(listOf(remote), availableModelCatalogs(setOf(ModelProvider.OPENAI), loading, registry, false)[ModelProvider.OPENAI])
    }
}
