package net.matsudamper.browser

import android.content.Context
import android.content.Intent

/**
 * Web Share API (navigator.share) の共有データを OS 共有シート向けの本文に組み立てる。
 * サイトが指定した空白・改行はそのまま保持する。
 */
internal fun buildWebShareBody(text: String?, uri: String?): String {
    return listOfNotNull(text, uri)
        .filter { it.trim().isNotEmpty() }
        .joinToString("\n")
}

internal fun hasWebShareContent(title: String?, text: String?, uri: String?): Boolean {
    return title?.trim()?.isNotEmpty() == true ||
        text?.trim()?.isNotEmpty() == true ||
        uri?.trim()?.isNotEmpty() == true
}

internal fun buildPlainTextShareIntent(body: String, subject: String?): Intent {
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        if (body.isNotEmpty()) {
            putExtra(Intent.EXTRA_TEXT, body)
        }
        if (subject != null) {
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
    }
}

internal fun canLaunchPlainTextShare(context: Context, body: String, subject: String?): Boolean {
    return buildPlainTextShareIntent(body, subject)
        .resolveActivity(context.packageManager) != null
}
