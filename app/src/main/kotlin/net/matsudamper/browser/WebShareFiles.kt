package net.matsudamper.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.Base64
import net.matsudamper.browser.feature.websharefiles.WebShareFilesWebExtension

internal data class WebShareFilePayload(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebShareFilePayload) return false
        return name == other.name &&
            mimeType == other.mimeType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

internal data class PreparedWebShareFiles(
    val intent: Intent,
    val cacheDir: File,
)

internal fun decodeWebShareFiles(
    files: List<WebShareFilesWebExtension.WebShareFile>,
): List<WebShareFilePayload> {
    return files.map { file ->
        WebShareFilePayload(
            name = file.name,
            mimeType = file.mimeType,
            bytes = Base64.getDecoder().decode(file.base64Data),
        )
    }
}

internal fun buildWebShareFilesIntent(
    title: String?,
    text: String?,
    url: String?,
    files: List<WebShareFilePayload>,
    uris: List<Uri>,
): Intent? {
    if (files.isEmpty() || uris.isEmpty() || files.size != uris.size) return null
    val body = buildWebShareBody(text, url)
    val subject = title?.trim()?.takeIf { it.isNotEmpty() }
    return if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = files.first().mimeType.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uris.first())
            if (body.isNotEmpty()) {
                putExtra(Intent.EXTRA_TEXT, body)
            }
            if (subject != null) {
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        val mimeType = files.map { it.mimeType }.distinct().singleOrNull()
            ?: "*/*"
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            if (body.isNotEmpty()) {
                putExtra(Intent.EXTRA_TEXT, body)
            }
            if (subject != null) {
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

internal fun prepareWebShareFilesIntent(
    context: Context,
    title: String?,
    text: String?,
    url: String?,
    files: List<WebShareFilePayload>,
): PreparedWebShareFiles? {
    if (files.isEmpty()) return null
    val cacheDir = File(context.cacheDir, "web_share_files").apply {
        deleteRecursively()
        mkdirs()
    }
    val uris = files.map { file ->
        val safeName = file.name.replace(Regex("[\\\\/]"), "_").ifBlank { "shared" }
        val outputFile = File(cacheDir, safeName)
        outputFile.writeBytes(file.bytes)
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.websharefileprovider",
            outputFile,
        )
    }
    val intent = buildWebShareFilesIntent(title, text, url, files, uris) ?: return null
    return PreparedWebShareFiles(intent = intent, cacheDir = cacheDir)
}

internal fun canLaunchWebShareFilesIntent(context: Context, intent: Intent): Boolean {
    return intent.resolveActivity(context.packageManager) != null
}
