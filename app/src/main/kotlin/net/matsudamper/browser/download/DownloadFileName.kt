package net.matsudamper.browser.download

import android.webkit.MimeTypeMap
import android.webkit.URLUtil
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

    fun resolve(urlString: String, contentDisposition: String?, mimeType: String): String {
        val guessedFileName = URLUtil.guessFileName(urlString, contentDisposition, null)
        if (hasExplicitFileName(contentDisposition)) {
            return guessedFileName.ifBlank { fallbackFileName() }
        }

        return correctUrlDerivedExtension(guessedFileName, mimeType)
            .ifBlank { fallbackFileName() }
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

    private fun hasExplicitFileName(contentDisposition: String?): Boolean {
        if (contentDisposition.isNullOrBlank()) {
            return false
        }
        return contentDisposition.split(';').any { parameter ->
            val separatorIndex = parameter.indexOf('=')
            if (separatorIndex <= 0) {
                false
            } else {
                val name = parameter.substring(0, separatorIndex).trim()
                val value = parameter.substring(separatorIndex + 1).trim().trim('"')
                (name.equals("filename", ignoreCase = true) ||
                    name.equals("filename*", ignoreCase = true)) &&
                    value.isNotEmpty()
            }
        }
    }

    private fun fallbackFileName(): String {
        return "download-${System.currentTimeMillis()}"
    }
}
