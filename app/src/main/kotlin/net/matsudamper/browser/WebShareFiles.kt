package net.matsudamper.browser

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import net.matsudamper.browser.feature.websharefiles.WebShareFilesWebExtension

internal const val WEB_SHARE_FILES_CHOSEN_ACTION =
    "net.matsudamper.browser.action.WEB_SHARE_FILES_CHOSEN"

internal const val EXTRA_WEB_SHARE_FILES_REQUEST_ID =
    "net.matsudamper.browser.extra.WEB_SHARE_FILES_REQUEST_ID"

/** 共有先アプリが URI を読み取る猶予。 */
internal const val WEB_SHARE_FILES_CACHE_RETENTION_MS = 10 * 60 * 1000L

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

internal fun sanitizeWebShareCacheFileName(name: String, index: Int): String {
    val normalized = name.replace(Regex("[\\\\/]"), "_").trim()
    val baseName = when {
        normalized.isEmpty() || normalized == "." || normalized == ".." -> "shared"
        else -> normalized
    }
    return "$index-$baseName"
}

internal fun decodeWebShareFiles(
    files: List<WebShareFilesWebExtension.WebShareFile>,
): List<WebShareFilePayload>? {
    return try {
        files.map { file ->
            WebShareFilePayload(
                name = file.name,
                mimeType = file.mimeType,
                bytes = Base64.getDecoder().decode(file.base64Data),
            )
        }
    } catch (_: IllegalArgumentException) {
        null
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
    requestId: String,
    title: String?,
    text: String?,
    url: String?,
    files: List<WebShareFilePayload>,
): PreparedWebShareFiles? {
    if (files.isEmpty() || requestId.isBlank()) return null
    val cacheDir = File(context.cacheDir, "web_share_files/$requestId").apply {
        mkdirs()
    }
    return try {
        val uris = files.mapIndexed { index, file ->
            val safeName = sanitizeWebShareCacheFileName(file.name, index)
            val outputFile = File(cacheDir, safeName)
            outputFile.writeBytes(file.bytes)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.websharefileprovider",
                outputFile,
            )
        }
        val intent = buildWebShareFilesIntent(title, text, url, files, uris) ?: return null
        PreparedWebShareFiles(intent = intent, cacheDir = cacheDir)
    } catch (_: IOException) {
        cacheDir.deleteRecursively()
        null
    } catch (_: IllegalArgumentException) {
        cacheDir.deleteRecursively()
        null
    }
}

internal fun buildWebShareFilesChooserIntent(
    context: Context,
    shareIntent: Intent,
    requestId: String,
): Intent {
    val callbackIntent = Intent(WEB_SHARE_FILES_CHOSEN_ACTION)
        .setPackage(context.packageName)
        .putExtra(EXTRA_WEB_SHARE_FILES_REQUEST_ID, requestId)
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        UUID.randomUUID().hashCode(),
        callbackIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
    return Intent.createChooser(shareIntent, null, pendingIntent.intentSender)
}

internal fun canLaunchWebShareFilesIntent(context: Context, intent: Intent): Boolean {
    return intent.resolveActivity(context.packageManager) != null
}
