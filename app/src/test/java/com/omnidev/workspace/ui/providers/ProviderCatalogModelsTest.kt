package com.omnidev.workspace.ui.providers

import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderCatalogModelsTest {
    @Test fun pickerAndRegistryShareCanonicalProviderQualifiedIds() {
        val model = AIModel("gpt-example", "Example", ModelProvider.OPENAI, contextWindow = 128_000)
        val result = normalizeCatalogModels(ModelProvider.OPENAI, listOf(model, model.copy(id = "OPENAI::gpt-example")))
        assertEquals(listOf("OPENAI::gpt-example"), result.map { it.id })
        assertEquals(result, normalizeCatalogModels(ModelProvider.OPENAI, result))
    }
    @Test fun copilotKeepsItsTransportSpecificIdentity() {
        val model = AIModel("copilot/example", "Example", ModelProvider.GITHUB_COPILOT, contextWindow = 128_000)
        assertEquals("copilot/example", normalizeCatalogModels(ModelProvider.GITHUB_COPILOT, listOf(model)).single().id)
    }
}
