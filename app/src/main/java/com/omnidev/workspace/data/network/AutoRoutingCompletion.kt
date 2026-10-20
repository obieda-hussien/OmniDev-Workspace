package com.omnidev.workspace.data.network

import com.omnidev.workspace.data.localllm.LocalEngineHolder
import com.omnidev.workspace.data.model.AttachmentMediaType
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.routing.AutoModelRouter
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider failover at the inference boundary, never at the tool-execution boundary.
 * Only explicitly selected Auto roles use it. No provider probing or billing requests.
 */
class AutoRoutingCompletion(
    private val service: CompletionService,
    private val settings: SettingsRepository,
    private val keys: ApiKeyRepository
) {
    private val unavailableUntil = ConcurrentHashMap<String, Long>()
    private val maxAttempts = 3

    suspend operator fun invoke(request: CompletionRequest): CompletionResponse =
        execute(request) { service(it) }

    suspend fun stream(
        request: CompletionRequest,
        onChunk: suspend (String) -> Unit
    ): CompletionResponse {
        var emittedContent = false
        return execute(request, mayFailover = { !emittedContent }) { candidate ->
            service.stream(candidate) { chunk ->
                if (chunk.isNotEmpty()) emittedContent = true
                onChunk(chunk)
            }
        }
    }

    private suspend fun execute(
        request: CompletionRequest,
        mayFailover: () -> Boolean = { true },
        call: suspend (CompletionRequest) -> CompletionResponse
    ): CompletionResponse {
        val role = request.autoRouteRole ?: return call(request)
        val policy = settings.observeAllModelAssignments().first()[role]
            ?: return call(request)
        if (!AutoModelRouter.isAuto(policy)) return call(request)

        val connected = keys.observeConfiguredProviders().first()
        val localReady = !settings.observeLocalModelUri().first().isNullOrBlank() &&
            LocalEngineHolder.engine.isLoaded
        val needsVision = request.messages.any { message ->
            message.attachments.any { it.mediaType == AttachmentMediaType.IMAGE }
        }
        val choices = AutoModelRouter.candidates(
            ModelRegistry.allModels, connected, role, policy, localReady, needsVision
        )
        if (choices.isEmpty()) throw IOException(
            if (policy == AutoModelRouter.FREE_ONLY)
                "Auto has no verified free model with the required capabilities. Connect a free model or explicitly choose paid fallback."
            else "Auto has no connected compatible model. Connect a provider in Settings."
        )

        // Never replay provider-specific tool-call signatures via a different model/provider.
        val hasExecutedToolHistory = request.messages.any {
            it.toolCalls.isNotEmpty() || it.toolResults.isNotEmpty()
        }
        val first = choices.firstOrNull { it.id == request.modelId } ?: choices.first()
        val attempts = (listOf(first) + choices.filterNot { it.id == first.id })
            .distinctBy { it.id }
            .filter { (unavailableUntil[it.id] ?: 0L) <= System.currentTimeMillis() }
            .ifEmpty { listOf(first) }
        var lastError: IOException? = null
        val exhaustedProviders = mutableSetOf<ModelProvider>()
        var attempted = 0
        for (model in attempts) {
            if (attempted >= maxAttempts) break
            if (model.provider in exhaustedProviders) continue
            if (attempted > 0 && (!mayFailover() || hasExecutedToolHistory)) break
            val key = if (model.provider == ModelProvider.LOCAL_EDGE) null
                else keys.getApiKey(model.provider)
            if (model.provider != ModelProvider.LOCAL_EDGE && key.isNullOrBlank()) continue
            attempted++
            val candidate = request.copy(
                modelId = model.id,
                apiKey = key,
                maxTokens = minOf(request.maxTokens, model.maxOutputTokens),
                enableThinking = request.enableThinking && model.supportsThinking,
                retryManagedByCaller = true
            )
            try {
                return call(candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                if (!recoverable(failure) || !mayFailover()) throw failure
                lastError = failure
                if (failure is RateLimitException ||
                    listOf("insufficient quota", "billing issue", "API key is invalid", "expired")
                        .any { failure.message.orEmpty().contains(it, ignoreCase = true) }) {
                    exhaustedProviders += model.provider
                }
                unavailableUntil[model.id] = System.currentTimeMillis() +
                    ((failure as? RateLimitException)?.retryAfterMs ?: 30_000L)
                        .coerceAtLeast(15_000L).coerceAtMost(15 * 60_000L)
            }
        }
        throw lastError ?: IOException("No available Auto model. Retry later or select a model manually.")
    }

    companion object {
        internal fun recoverable(error: IOException): Boolean =
            error is RateLimitException || listOf(
                "rate limit", "too many requests", "503", "502", "500", "504",
                "server encountered", "service unavailable", "temporarily unavailable",
                "timed out", "model not found", "not have permission", "insufficient quota",
                "billing issue", "API key is invalid", "expired"
            ).any { error.message.orEmpty().contains(it, ignoreCase = true) }
    }
}
