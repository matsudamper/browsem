package net.matsudamper.browser.download

import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.util.Locale

object DownloadFileName {
    private val genericMimeTypes = setOf(
        "application/octet-stream",
        "binary/octet-stream",
        "octet/stream",
        "application/force-download",
        "application/download",
        "application/x-download",
        "text/plain",
    )

    private val extendedFileNamePattern = Regex(
        """(?:^|;)\s*filename\*\s*=\s*([^';\s]*)'[^']*'([^;\s]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val plainFileNamePattern = Regex(
        """(?:^|;)\s*filename\s*=\s*(?:"((?:\\.|[^"\\])*)"|([^;]+))""",
        RegexOption.IGNORE_CASE,
    )
    private val quotedPairPattern = Regex("""\\(.)""")

    fun resolve(urlString: String, contentDisposition: String?, mimeType: String): String {
        val dispositionFileName = contentDisposition?.let { parseContentDispositionFileName(it) }
        if (dispositionFileName != null) {
            return dispositionFileName
        }

        val urlFileName = URLUtil.guessFileName(urlString, null, null)
        return correctUrlDerivedExtension(urlFileName, mimeType)
            .ifBlank { fallbackFileName() }
    }

    /**
     * URLUtil.guessFileName は古い OS では filename* や inline を解釈できず、
     * URL 由来の名前にフォールバックしてしまうため、Content-Disposition は自前で解析する。
     */
    private fun parseContentDispositionFileName(contentDisposition: String): String? {
        val extendedFileName = extendedFileNamePattern.find(contentDisposition)?.let { match ->
            decodeExtendedValue(charsetName = match.groupValues[1], encodedValue = match.groupValues[2])
        }
        val fileName = extendedFileName
            ?: plainFileNamePattern.find(contentDisposition)?.let { match ->
                match.groups[1]?.value?.replace(quotedPairPattern, "$1")
                    ?: match.groupValues[2]
            }
        return fileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun decodeExtendedValue(charsetName: String, encodedValue: String): String? {
        return try {
            // RFC 5987 では '+' は空白ではないため、URLDecoder に解釈させないよう退避する
            URLDecoder.decode(encodedValue.replace("+", "%2B"), charsetName.ifEmpty { "UTF-8" })
        } catch (_: UnsupportedEncodingException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun correctUrlDerivedExtension(fileName: String, mimeType: String): String {
        val normalizedMimeType = mimeType.lowercase(Locale.US)
        if (normalizedMimeType in genericMimeTypes) {
            return fileName
        }

        val mimeTypeMap = MimeTypeMap.getSingleton()
        val expectedExtension = mimeTypeMap.getExtensionFromMimeType(normalizedMimeType)
            ?.lowercase(Locale.US)
            ?: return fileName
        val currentExtension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.US)

        if (currentExtension == expectedExtension) {
            return fileName
        }

        val currentMimeType = currentExtension
            .takeIf { it.isNotEmpty() && it != "bin" }
            ?.let { mimeTypeMap.getMimeTypeFromExtension(it) }
        if (currentMimeType != null) {
            return fileName
        }

        if (currentExtension.isNotEmpty() && currentExtension.all(Char::isDigit)) {
            return "$fileName.$expectedExtension"
        }

        val baseName = if (currentExtension.isEmpty()) {
            fileName
        } else {
            fileName.substringBeforeLast('.')
        }
        return "$baseName.$expectedExtension"
    }

    private fun fallbackFileName(): String {
        return "download-${System.currentTimeMillis()}"
    }
}
