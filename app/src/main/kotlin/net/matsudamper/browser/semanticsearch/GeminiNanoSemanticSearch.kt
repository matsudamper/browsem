package net.matsudamper.browser.semanticsearch

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import net.matsudamper.browser.translate.GeminiNanoModel
import net.matsudamper.browser.translate.PageTranslationWebExtension

internal class GeminiNanoSemanticSearch(
    private val modelKey: String,
) {
    private val inferenceMutex = Mutex()

    suspend fun rankMatchingSegmentIds(
        query: String,
        candidates: List<PageTranslationWebExtension.Segment>,
    ): List<String> {
        if (candidates.isEmpty()) return listOf()
        val selectedModel = GeminiNanoModel.select(modelKey)
            ?: throw IllegalStateException("Gemini Nanoを利用できない端末です")
        try {
            prepareModel(selectedModel.generativeModel)
            val prompt = buildSemanticSearchPrompt(
                query = query,
                candidates = candidates,
                maxSegmentChars = MAX_SEGMENT_CHARS_IN_PROMPT,
            )
            val output = generate(selectedModel.generativeModel, prompt)
            return parseSemanticSearchSegmentIds(
                modelOutput = output,
                allowedIds = candidates.map { it.id }.toSet(),
            )
        } finally {
            selectedModel.generativeModel.close()
        }
    }

    private suspend fun prepareModel(generativeModel: GenerativeModel) {
        withTimeout(MODEL_PREPARATION_TIMEOUT_MS) {
            while (true) {
                when (generativeModel.checkStatus()) {
                    FeatureStatus.AVAILABLE -> {
                        generativeModel.warmup()
                        return@withTimeout
                    }

                    FeatureStatus.DOWNLOADABLE -> {
                        generativeModel.download().collect { status ->
                            if (status is DownloadStatus.DownloadFailed) {
                                throw status.e
                            }
                        }
                    }

                    FeatureStatus.DOWNLOADING -> delay(MODEL_STATUS_POLL_INTERVAL_MS)

                    FeatureStatus.UNAVAILABLE -> {
                        throw IllegalStateException("Gemini Nanoを利用できない端末です")
                    }

                    else -> throw IllegalStateException("Gemini Nanoの利用状態を判定できませんでした")
                }
            }
        }
    }

    private suspend fun generate(generativeModel: GenerativeModel, prompt: String): String {
        val configure: GenerateContentRequest.Builder.() -> Unit = {
            temperature = 0f
            topK = 1
            candidateCount = 1
            maxOutputTokens = MAX_OUTPUT_TOKENS
            enableThinking = false
        }
        val systemPromptAvailable = runCatching { generativeModel.isSystemPromptAvailable() }
            .getOrDefault(false)
        val request = if (systemPromptAvailable) {
            generateContentRequest(
                SystemInstruction(SEMANTIC_SEARCH_SYSTEM_INSTRUCTION),
                TextPart(prompt),
                configure,
            )
        } else {
            generateContentRequest(
                TextPart("$SEMANTIC_SEARCH_SYSTEM_INSTRUCTION\n\n$prompt"),
                configure,
            )
        }
        val response = inferenceMutex.withLock {
            withTimeout(GENERATION_TIMEOUT_MS) {
                generativeModel.generateContent(request)
            }
        }
        return response.candidates
            .firstOrNull()
            ?.text
            ?.trim()
            .orEmpty()
    }

    companion object {
        private const val SEMANTIC_SEARCH_SYSTEM_INSTRUCTION =
            "You select segment IDs that answer the user's question. Output only comma-separated IDs or NONE."
        private const val MAX_SEGMENT_CHARS_IN_PROMPT = 120
        private const val MAX_OUTPUT_TOKENS = 96
        private const val MODEL_PREPARATION_TIMEOUT_MS = 120_000L
        private const val MODEL_STATUS_POLL_INTERVAL_MS = 500L
        private const val GENERATION_TIMEOUT_MS = 45_000L
    }
}
