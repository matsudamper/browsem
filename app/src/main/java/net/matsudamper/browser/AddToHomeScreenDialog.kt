package net.matsudamper.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * ホームへの追加方法を選択するダイアログ。
 * ショートカット（ブラウザで開く）とアプリとして追加の2択を提供する。
 */
@Composable
internal fun AddToHomeScreenDialog(
    url: String,
    title: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ホームに追加") },
        text = { Text(title.ifBlank { url }) },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        addShortcutToHome(context, url, title)
                        onDismiss()
                    },
                ) {
                    Text("ショートカット")
                }
                TextButton(
                    onClick = {
                        addWebAppToHome(context, url, title)
                        onDismiss()
                    },
                ) {
                    Text("アプリ")
                }
            }
        },
    )
}

/**
 * ホーム画面にショートカットを追加する。
 * ショートカットはメインブラウザでURLを開く。
 */
private fun addShortcutToHome(context: Context, url: String, title: String) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        Toast.makeText(context, "ランチャーがショートカット追加に対応していません", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url), context, MainActivity::class.java)
    val info = ShortcutInfoCompat.Builder(context, "shortcut_${url.hashCode()}")
        .setShortLabel(title.ifBlank { url }.take(25))
        .setLongLabel(title.ifBlank { url })
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_firefox_like))
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.requestPinShortcut(context, info, null)
}

/**
 * ホーム画面にアプリとして追加する。
 * 専用の WebAppActivity で開き、独立したタスクとして管理される。
 */
private fun addWebAppToHome(context: Context, url: String, title: String) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        Toast.makeText(context, "ランチャーがショートカット追加に対応していません", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(context, WebAppActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse(url)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val info = ShortcutInfoCompat.Builder(context, "webapp_${url.hashCode()}")
        .setShortLabel(title.ifBlank { url }.take(25))
        .setLongLabel(title.ifBlank { url })
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_firefox_like))
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.requestPinShortcut(context, info, null)
}
