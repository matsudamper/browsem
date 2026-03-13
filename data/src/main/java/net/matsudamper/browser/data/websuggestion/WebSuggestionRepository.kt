package net.matsudamper.browser.data.websuggestion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.SearchProvider
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

                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    parseSuggestionResponse(
                        searchProvider = request.searchProvider,
                        body = reader.readText(),
                    )
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
    val suggestionsArray = extractTopLevelValue(body, index = 1) ?: return emptyList()
    return parseJsonStringArray(suggestionsArray)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctPreservingOrder()
}

private fun parseDuckDuckGoSuggestions(body: String): List<String> {
    return parseTopLevelArrayValues(body)
        .mapNotNull { extractObjectStringValue(it, key = "phrase") }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctPreservingOrder()
}

private fun List<String>.distinctPreservingOrder(): List<String> {
    val seen = mutableSetOf<String>()
    return filter { seen.add(it.lowercase(Locale.ROOT)) }
}

private fun parseTopLevelArrayValues(body: String): List<String> {
    val values = mutableListOf<String>()
    var index = skipWhitespace(body, 0)
    if (index >= body.length || body[index] != '[') {
        return emptyList()
    }
    index++

    while (true) {
        index = skipWhitespace(body, index)
        if (index >= body.length || body[index] == ']') {
            return values
        }

        val endIndex = skipJsonValue(body, index)
        values += body.substring(index, endIndex)

        index = skipWhitespace(body, endIndex)
        if (index < body.length && body[index] == ',') {
            index++
            continue
        }
        return values
    }
}

private fun extractTopLevelValue(body: String, index: Int): String? {
    return parseTopLevelArrayValues(body).getOrNull(index)
}

private fun parseJsonStringArray(body: String): List<String> {
    val values = mutableListOf<String>()
    var index = skipWhitespace(body, 0)
    if (index >= body.length || body[index] != '[') {
        return emptyList()
    }
    index++

    while (true) {
        index = skipWhitespace(body, index)
        if (index >= body.length || body[index] == ']') {
            return values
        }
        if (body[index] != '"') {
            val endIndex = skipJsonValue(body, index)
            index = skipWhitespace(body, endIndex)
            if (index < body.length && body[index] == ',') {
                index++
                continue
            }
            return values
        }

        val endIndex = skipJsonString(body, index)
        values += decodeJsonString(body.substring(index + 1, endIndex - 1))

        index = skipWhitespace(body, endIndex)
        if (index < body.length && body[index] == ',') {
            index++
            continue
        }
        return values
    }
}

private fun extractObjectStringValue(
    body: String,
    key: String,
): String? {
    var index = skipWhitespace(body, 0)
    if (index >= body.length || body[index] != '{') {
        return null
    }
    index++

    while (true) {
        index = skipWhitespace(body, index)
        if (index >= body.length || body[index] == '}') {
            return null
        }
        if (body[index] != '"') {
            return null
        }

        val keyEndIndex = skipJsonString(body, index)
        val currentKey = decodeJsonString(body.substring(index + 1, keyEndIndex - 1))

        index = skipWhitespace(body, keyEndIndex)
        if (index >= body.length || body[index] != ':') {
            return null
        }
        index = skipWhitespace(body, index + 1)
        if (index >= body.length) {
            return null
        }

        val valueEndIndex = skipJsonValue(body, index)
        if (currentKey == key && body[index] == '"') {
            return decodeJsonString(body.substring(index + 1, valueEndIndex - 1))
        }

        index = skipWhitespace(body, valueEndIndex)
        if (index < body.length && body[index] == ',') {
            index++
            continue
        }
        return null
    }
}

private fun skipJsonValue(
    body: String,
    startIndex: Int,
): Int {
    var index = skipWhitespace(body, startIndex)
    if (index >= body.length) {
        return index
    }
    return when (body[index]) {
        '"' -> skipJsonString(body, index)
        '[' -> skipJsonContainer(body, index, '[', ']')
        '{' -> skipJsonContainer(body, index, '{', '}')
        else -> {
            while (index < body.length && body[index] !in ",]}") {
                index++
            }
            index
        }
    }
}

private fun skipJsonString(
    body: String,
    startIndex: Int,
): Int {
    var index = startIndex + 1
    while (index < body.length) {
        when (body[index]) {
            '\\' -> index += 2
            '"' -> return index + 1
            else -> index++
        }
    }
    return body.length
}

private fun skipJsonContainer(
    body: String,
    startIndex: Int,
    openChar: Char,
    closeChar: Char,
): Int {
    var index = startIndex
    var depth = 0
    var inString = false

    while (index < body.length) {
        val currentChar = body[index]
        if (inString) {
            if (currentChar == '\\') {
                index += 2
                continue
            }
            if (currentChar == '"') {
                inString = false
            }
            index++
            continue
        }

        when (currentChar) {
            '"' -> inString = true
            openChar -> depth++
            closeChar -> {
                depth--
                if (depth == 0) {
                    return index + 1
                }
            }
        }
        index++
    }
    return body.length
}

private fun decodeJsonString(value: String): String {
    val result = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val currentChar = value[index]
        if (currentChar != '\\' || index == value.lastIndex) {
            result.append(currentChar)
            index++
            continue
        }

        when (val escaped = value[index + 1]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000C')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
                val unicodeEnd = (index + 6).coerceAtMost(value.length)
                val unicodeValue = value.substring(index + 2, unicodeEnd)
                result.append(unicodeValue.toInt(16).toChar())
                index += 4
            }
            else -> result.append(escaped)
        }
        index += 2
    }
    return result.toString()
}

private fun skipWhitespace(
    body: String,
    startIndex: Int,
): Int {
    var index = startIndex
    while (index < body.length && body[index].isWhitespace()) {
        index++
    }
    return index
}

private const val USER_AGENT = "browsem/1.0"
private const val REQUEST_TIMEOUT_MILLIS = 5_000
