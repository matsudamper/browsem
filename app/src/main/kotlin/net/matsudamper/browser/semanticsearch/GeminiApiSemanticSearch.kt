package net.matsudamper.browser.semanticsearch

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.matsudamper.browser.translate.PageTranslationWebExtension
import org.json.JSONArray
import org.json.JSONObject

internal class GeminiApiSemanticSearch(
    private val apiKey: String,
    private val modelId: String,
) {
    suspend fun rankMatchingSegmentIds(
        query: String,
        candidates: List<PageTranslationWebExtension.Segment>,
    ): List<String> {
        if (candidates.isEmpty()) return listOf()
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            throw IllegalStateException("Google AI StudioのAPIキーが設定されていません")
        }
        val prompt = buildSemanticSearchPrompt(
            query = query,
            candidates = candidates,
            maxSegmentChars = MAX_SEGMENT_CHARS_IN_PROMPT,
        )
        val output = withContext(Dispatchers.IO) {
            withTimeout(REQUEST_TIMEOUT_MS) {
                requestGenerateContent(trimmedKey, modelId, prompt)
            }
        }
        return parseSemanticSearchSegmentIds(
            modelOutput = output,
            allowedIds = candidates.map { it.id }.toSet(),
        )
    }

    private fun requestGenerateContent(apiKey: String, modelId: String, userPrompt: String): String {
        val endpoint = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent",
        )
        val body = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().apply {
                    put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("text", SEMANTIC_SEARCH_SYSTEM_INSTRUCTION),
                        ),
                    )
                },
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", userPrompt),
                            ),
                        )
                    },
                ),
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0)
                    put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                },
            )
        }
        val connection = endpoint.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("x-goog-api-key", apiKey)
        connection.outputStream.use { stream ->
            stream.write(body.toString().toByteArray(Charsets.UTF_8))
        }
        val statusCode = connection.responseCode
        val responseText = readResponseBody(connection, statusCode)
        if (statusCode !in HTTP_OK_MIN..HTTP_OK_MAX) {
            throw IllegalStateException(parseApiErrorMessage(statusCode, responseText))
        }
        return extractGeneratedText(responseText)
    }

    private fun readResponseBody(connection: HttpURLConnection, statusCode: Int): String {
        val stream = if (statusCode in HTTP_OK_MIN..HTTP_OK_MAX) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun extractGeneratedText(responseText: String): String {
        val root = JSONObject(responseText)
        val candidates = root.optJSONArray("candidates") ?: return ""
        val first = candidates.optJSONObject(0) ?: return ""
        val content = first.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val textBuilder = StringBuilder()
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            val text = part.optString("text")
            if (text.isNotBlank()) {
                textBuilder.append(text)
            }
        }
        return textBuilder.toString().trim()
    }

    private fun parseApiErrorMessage(statusCode: Int, responseText: String): String {
        val apiMessage = runCatching {
            JSONObject(responseText).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return when {
            statusCode == HttpURLConnection.HTTP_UNAUTHORIZED || statusCode == HttpURLConnection.HTTP_FORBIDDEN ->
                apiMessage ?: "Google AI StudioのAPIキーが無効です"

            statusCode == HttpURLConnection.HTTP_NOT_FOUND ->
                apiMessage ?: "指定したGeminiモデルにアクセスできません"

            apiMessage != null -> apiMessage

            else -> "Gemini APIの呼び出しに失敗しました (HTTP $statusCode)"
        }
    }

    companion object {
        private const val SEMANTIC_SEARCH_SYSTEM_INSTRUCTION =
            "You select segment IDs that semantically answer the user's question, " +
                "even when the exact query words are absent (e.g. question 価格 → segment 900円). " +
                "Do not match query words only literally. Output only comma-separated IDs or NONE."
        private const val MAX_SEGMENT_CHARS_IN_PROMPT = 200
        private const val MAX_OUTPUT_TOKENS = 96
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val REQUEST_TIMEOUT_MS = 35_000L
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
    }
}
