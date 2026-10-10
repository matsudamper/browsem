package net.matsudamper.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

internal fun copyUrlToClipboard(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
    Toast.makeText(context, "URLをコピーしました", Toast.LENGTH_SHORT).show()
}

internal fun copyLinkTextToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("リンクテキスト", text))
    Toast.makeText(context, "リンクテキストをコピーしました", Toast.LENGTH_SHORT).show()
}
