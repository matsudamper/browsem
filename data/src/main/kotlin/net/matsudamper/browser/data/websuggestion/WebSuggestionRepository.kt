package net.matsudamper.browser.data.websuggestion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.SearchProvider
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

interface WebSuggestionRepository {
    suspend fun getSuggestions(
        searchProvider: SearchProvider,
        query: String,
    ): List<String>
}

class HttpWebSuggestionRepository(
    private val connectionFactory: UrlConnectionFactory = DefaultUrlConnectionFactory,
) : WebSuggestionRepository {
    override suspend fun getSuggestions(
        searchProvider: SearchProvider,
        query: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val request = buildSuggestionRequest(
            searchProvider = searchProvider,
            query = query,
        ) ?: return@withContext emptyList()

        runCatching {
            val connection = connectionFactory.open(request.url)
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = REQUEST_TIMEOUT_MILLIS
                connection.readTimeout = REQUEST_TIMEOUT_MILLIS
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", USER_AGENT)

                if (connection.responseCode !in 200..299) {
                    return@runCatching emptyList()
                }

                runInterruptible {
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        parseSuggestionResponse(
                            searchProvider = request.searchProvider,
                            body = reader.readText(),
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        }.onFailure { e ->
            // キャンセル時は再スローしてコルーチンのキャンセルを正しく伝播させる
            if (e is CancellationException) throw e
        }.getOrDefault(emptyList())
    }
}

internal data class SuggestionRequest(
    val searchProvider: SearchProvider,
    val url: String,
)

interface UrlConnectionFactory {
    fun open(url: String): HttpURLConnection
}

internal object DefaultUrlConnectionFactory : UrlConnectionFactory {
    override fun open(url: String): HttpURLConnection {
        return URL(url).openConnection() as HttpURLConnection
    }
}

internal fun buildSuggestionRequest(
    searchProvider: SearchProvider,
    query: String,
): SuggestionRequest? {
    val trimmed = query.trim()
    if (trimmed.isBlank()) {
        return null
    }
    val encodedQuery = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
    return when (searchProvider) {
        SearchProvider.GOOGLE -> SuggestionRequest(
            searchProvider = searchProvider,
            url = "https://www.google.com/complete/search?client=firefox&q=$encodedQuery",
        )

        SearchProvider.DUCKDUCKGO -> SuggestionRequest(
            searchProvider = searchProvider,
            url = "https://ac.duckduckgo.com/ac/?q=$encodedQuery&type=list",
        )

        SearchProvider.CUSTOM,
        SearchProvider.UNRECOGNIZED,
        -> null
    }
}

internal fun parseSuggestionResponse(
    searchProvider: SearchProvider,
    body: String,
): List<String> {
    return when (searchProvider) {
        SearchProvider.GOOGLE -> parseGoogleSuggestions(body)
        SearchProvider.DUCKDUCKGO -> parseDuckDuckGoSuggestions(body)
        SearchProvider.CUSTOM,
        SearchProvider.UNRECOGNIZED,
        -> emptyList()
    }
}

private fun parseGoogleSuggestions(body: String): List<String> {
    return try {
        val topArray = JSONArray(body)
        val suggestionsArray = topArray.getJSONArray(1)
        (0 until suggestionsArray.length())
            .map { suggestionsArray.getString(it).trim() }
            .filter { it.isNotEmpty() }
            .distinctPreservingOrder()
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseDuckDuckGoSuggestions(body: String): List<String> {
    return try {
        val array = JSONArray(body)
        (0 until array.length())
            .mapNotNull { array.getJSONObject(it).optString("phrase").takeIf { s -> s.isNotEmpty() } }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctPreservingOrder()
    } catch (_: Exception) {
        emptyList()
    }
}

private fun List<String>.distinctPreservingOrder(): List<String> {
    val seen = mutableSetOf<String>()
    return filter { seen.add(it.lowercase(Locale.ROOT)) }
}

private const val USER_AGENT = "browsem/1.0"
private const val REQUEST_TIMEOUT_MILLIS = 5_000
