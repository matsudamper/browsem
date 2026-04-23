package net.matsudamper.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

internal object HomeScreenIconFetcher {
    private const val CONNECTION_TIMEOUT_MS = 2500
    private const val FETCH_TIMEOUT_MS = 8000
    private const val MAX_HTML_BYTES = 256 * 1024
    private const val MAX_SHORTCUT_ICON_SIZE = 192
    private const val MAX_ICON_FETCH_ATTEMPTS = 20
    private const val USER_AGENT = "Mozilla/5.0 (Android) Browsem"

    private val linkTagRegex = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attributeRegex =
        Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""")
    private val iconSizeRegex = Regex("""(\d+)x(\d+)""", RegexOption.IGNORE_CASE)

    suspend fun fetchIcon(pageUrl: String, webAppManifestJson: String?): Bitmap? {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(FETCH_TIMEOUT_MS.toLong()) {
                fetchIconBlocking(pageUrl, webAppManifestJson)
            }
        }
    }

    private fun fetchIconBlocking(pageUrl: String, webAppManifestJson: String?): Bitmap? {
        val pageUri = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        if (!pageUri.isHttpUri() || pageUri.host.isNullOrBlank()) return null

        val pageHtmlIcons = fetchText(pageUri.toString(), MAX_HTML_BYTES)
            ?.let { parseHtmlIconCandidates(it, pageUri) }
            ?: HtmlIconCandidates()
        val storedManifestBaseUri = pageHtmlIcons.manifestUrls
            .firstNotNullOfOrNull { manifestUrl ->
                runCatching { URI(manifestUrl) }
                    .getOrNull()
                    ?.takeIf { it.isHttpUri() }
            }
            ?: pageUri
        val storedManifestIcons = parseManifestIconCandidates(
            manifestJson = webAppManifestJson,
            fallbackBaseUri = storedManifestBaseUri,
            source = IconSource.StoredManifest,
        )
        val linkedManifestIcons = pageHtmlIcons.manifestUrls
            .take(2)
            .flatMap { manifestUrl ->
                val manifestUri = runCatching { URI(manifestUrl) }.getOrNull() ?: return@flatMap emptyList()
                parseManifestIconCandidates(
                    manifestJson = fetchText(manifestUrl, MAX_HTML_BYTES),
                    fallbackBaseUri = manifestUri,
                    source = IconSource.LinkedManifest,
                )
            }
        val candidates = buildList {
            addAll(storedManifestIcons)
            addAll(linkedManifestIcons)
            addAll(pageHtmlIcons.icons)
            addAll(fallbackIconCandidates(pageUri))
        }
            .filterNot { it.isKnownUnsupportedImage() }
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<IconCandidate> { it.source.priority }
                    .thenByDescending { it.size },
            )

        var attemptCount = 0
        for (candidate in candidates) {
            if (attemptCount >= MAX_ICON_FETCH_ATTEMPTS) break
            attemptCount++
            val bitmap = fetchBitmap(candidate.url) ?: continue
            return bitmap.scaleForShortcut()
        }
        return null
    }

    private fun parseHtmlIconCandidates(html: String, pageUri: URI): HtmlIconCandidates {
        val icons = mutableListOf<IconCandidate>()
        val manifestUrls = mutableListOf<String>()
        linkTagRegex.findAll(html).forEach { result ->
            val attributes = parseAttributes(result.value)
            val relValues = attributes["rel"]
                ?.lowercase(Locale.US)
                ?.split(Regex("""\s+"""))
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: return@forEach
            val href = attributes["href"]?.takeIf { it.isNotBlank() } ?: return@forEach
            if ("manifest" in relValues) {
                resolveUrl(pageUri, href)?.also(manifestUrls::add)
            }
            if (relValues.any { it == "icon" || it == "apple-touch-icon" || it == "apple-touch-icon-precomposed" }) {
                val url = resolveUrl(pageUri, href) ?: return@forEach
                icons += IconCandidate(
                    url = url,
                    size = parseIconSize(attributes["sizes"]),
                    type = attributes["type"],
                    source = IconSource.Html,
                )
            }
        }
        return HtmlIconCandidates(
            icons = icons,
            manifestUrls = manifestUrls.distinct(),
        )
    }

    private fun parseAttributes(tag: String): Map<String, String> {
        return attributeRegex.findAll(tag).associate { result ->
            val key = result.groupValues[1].lowercase(Locale.US)
            val value = result.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
            key to value
        }
    }

    private fun parseManifestIconCandidates(
        manifestJson: String?,
        fallbackBaseUri: URI,
        source: IconSource,
    ): List<IconCandidate> {
        if (manifestJson.isNullOrBlank()) return emptyList()
        val manifest = runCatching { JSONObject(manifestJson) }.getOrNull() ?: return emptyList()
        val manifestBaseUri = manifest.optString("href")
            .takeIf { it.isNotBlank() }
            ?.let { resolveUrl(fallbackBaseUri, it) }
            ?.let { runCatching { URI(it) }.getOrNull() }
            ?: fallbackBaseUri
        val icons = manifest.optJSONArray("icons") ?: return emptyList()
        return buildList {
            for (index in 0 until icons.length()) {
                val icon = icons.optJSONObject(index) ?: continue
                val purposeTokens = icon.optString("purpose")
                    .takeIf { it.isNotBlank() }
                    ?.lowercase(Locale.US)
                    ?.split(Regex("""\s+"""))
                    ?.filter { it.isNotBlank() }
                // purpose が monochrome だけを含む場合はショートカット用途に使えないのでスキップする。
                // "any monochrome" のように他の用途も含むアイコンは候補として残す。
                if (purposeTokens != null &&
                    "monochrome" in purposeTokens &&
                    "any" !in purposeTokens &&
                    "maskable" !in purposeTokens
                ) {
                    continue
                }
                val src = icon.optString("src").takeIf { it.isNotBlank() } ?: continue
                val url = resolveUrl(manifestBaseUri, src) ?: continue
                add(
                    IconCandidate(
                        url = url,
                        size = parseIconSize(icon.optString("sizes")),
                        type = icon.optString("type").takeIf { it.isNotBlank() },
                        source = source,
                    ),
                )
            }
        }
    }

    private fun fallbackIconCandidates(pageUri: URI): List<IconCandidate> {
        val originUri = runCatching { URI("${pageUri.scheme}://${pageUri.rawAuthority}/") }.getOrNull()
            ?: return emptyList()
        return listOf(
            "/apple-touch-icon.png",
            "/apple-touch-icon-precomposed.png",
            "/favicon.png",
            "/favicon.ico",
        ).mapNotNull { path ->
            resolveUrl(originUri, path)?.let { url ->
                IconCandidate(
                    url = url,
                    size = 0,
                    type = null,
                    source = IconSource.Fallback,
                )
            }
        }
    }

    private fun fetchText(url: String, maxBytes: Int): String? {
        val connection = openHttpConnection(url, "text/html,application/manifest+json,application/json,*/*")
            ?: return null
        return try {
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalBytes = 0
                while (totalBytes < maxBytes) {
                    val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - totalBytes))
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    totalBytes += read
                }
                output.toByteArray()
            }
            String(bytes, parseCharset(connection.contentType))
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchBitmap(url: String): Bitmap? {
        val connection = openHttpConnection(url, "image/avif,image/webp,image/png,image/jpeg,image/*,*/*")
            ?: return null
        return try {
            if (connection.responseCode !in 200..299) return null
            val contentType = connection.contentType
                ?.substringBefore(";")
                ?.trim()
                ?.lowercase(Locale.US)
            if (contentType == "image/svg+xml") return null
            BufferedInputStream(connection.inputStream).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttpConnection(url: String, accept: String): HttpURLConnection? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.isHttpUri()) return null
        val connection = runCatching { URL(url).openConnection() as? HttpURLConnection }.getOrNull()
            ?: return null
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECTION_TIMEOUT_MS
        connection.readTimeout = CONNECTION_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", accept)
        return connection
    }

    private fun parseCharset(contentType: String?): Charset {
        val charsetName = contentType
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim('"')
        return charsetName
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8
    }

    private fun parseIconSize(sizes: String?): Int {
        if (sizes.isNullOrBlank()) return 0
        return iconSizeRegex.findAll(sizes)
            .mapNotNull { result ->
                val width = result.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val height = result.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                max(width, height)
            }
            .maxOrNull()
            ?: 0
    }

    private fun resolveUrl(baseUri: URI, rawUrl: String): String? {
        return runCatching { baseUri.resolve(rawUrl).toString() }
            .getOrNull()
            ?.takeIf { resolvedUrl ->
                val uri = runCatching { URI(resolvedUrl) }.getOrNull()
                uri?.isHttpUri() == true
            }
    }

    private fun URI.isHttpUri(): Boolean {
        val normalizedScheme = scheme?.lowercase(Locale.US)
        return normalizedScheme == "http" || normalizedScheme == "https"
    }

    private fun IconCandidate.isKnownUnsupportedImage(): Boolean {
        val normalizedType = type
            ?.substringBefore(";")
            ?.trim()
            ?.lowercase(Locale.US)
        if (normalizedType == "image/svg+xml") return true
        val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.US) }.getOrDefault("")
        return path.endsWith(".svg")
    }

    private fun Bitmap.scaleForShortcut(): Bitmap {
        val maxDimension = max(width, height)
        if (maxDimension <= MAX_SHORTCUT_ICON_SIZE) return this
        val scale = MAX_SHORTCUT_ICON_SIZE.toFloat() / maxDimension.toFloat()
        val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
    }

    private data class HtmlIconCandidates(
        val icons: List<IconCandidate> = emptyList(),
        val manifestUrls: List<String> = emptyList(),
    )

    private data class IconCandidate(
        val url: String,
        val size: Int,
        val type: String?,
        val source: IconSource,
    )

    private enum class IconSource(val priority: Int) {
        StoredManifest(priority = 4),
        LinkedManifest(priority = 3),
        Html(priority = 2),
        Fallback(priority = 1),
    }
}
