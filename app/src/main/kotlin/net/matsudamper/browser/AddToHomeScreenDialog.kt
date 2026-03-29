package net.matsudamper.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import net.matsudamper.browser.ui.common.BrowserTheme

/**
 * ホームへの追加方法を選択するダイアログ。
 * ショートカット（ブラウザで開く）とアプリとして追加の2択を提供する。
 */
@Composable
internal fun AddToHomeScreenDialog(
    url: String,
    title: String,
    favicon: Bitmap?,
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
                        addShortcutToHome(context, url, title, favicon)
                        onDismiss()
                    },
                ) {
                    Text("ショートカット")
                }
                TextButton(
                    onClick = {
                        addWebAppToHome(context, url, title, favicon)
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
 * ショートカットはアプリの http/https ディープリンクハンドラ経由でURLを開く。
 */
private fun addShortcutToHome(context: Context, url: String, title: String, favicon: Bitmap?) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        Toast.makeText(context, "ランチャーがショートカット追加に対応していません", Toast.LENGTH_SHORT).show()
        return
    }
    // DeepLinkActivity を経由することでアプリの http/https VIEW ルーティングを正しく使用する
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url), context, DeepLinkActivity::class.java)
    val icon = if (favicon != null) {
        IconCompat.createWithBitmap(favicon)
    } else {
        IconCompat.createWithResource(context, R.drawable.ic_firefox_like)
    }
    val info = ShortcutInfoCompat.Builder(context, "shortcut_${url.hashCode()}")
        .setShortLabel(title.ifBlank { url }.take(25))
        .setLongLabel(title.ifBlank { url })
        .setIcon(icon)
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.requestPinShortcut(context, info, null)
}

/**
 * ホーム画面にアプリとして追加する。
 * 専用の WebAppActivity で開き、ドキュメントタスクとして独立したRecentsエントリを持つ。
 */
private fun addWebAppToHome(context: Context, url: String, title: String, favicon: Bitmap?) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        Toast.makeText(context, "ランチャーがショートカット追加に対応していません", Toast.LENGTH_SHORT).show()
        return
    }
    // FLAG_ACTIVITY_NEW_DOCUMENT により各ショートカットが独立したRecentsエントリを持つ
    val intent = Intent(context, WebAppActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse(url)
        addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
    }
    val icon = if (favicon != null) {
        IconCompat.createWithBitmap(favicon)
    } else {
        IconCompat.createWithResource(context, R.drawable.ic_firefox_like)
    }
    val info = ShortcutInfoCompat.Builder(context, "webapp_${url.hashCode()}")
        .setShortLabel(title.ifBlank { url }.take(25))
        .setLongLabel(title.ifBlank { url })
        .setIcon(icon)
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.requestPinShortcut(context, info, null)
}

@Preview(name = "favicon あり")
@Composable
private fun PreviewWithFavicon() {
    BrowserTheme(themeMode = net.matsudamper.browser.data.ThemeMode.THEME_SYSTEM) {
        AddToHomeScreenDialog(
            url = "https://example.com",
            title = "Example Site",
            favicon = null,
            onDismiss = {},
        )
    }
}

@Preview(name = "タイトルなし")
@Composable
private fun PreviewNoTitle() {
    BrowserTheme(themeMode = net.matsudamper.browser.data.ThemeMode.THEME_SYSTEM) {
        AddToHomeScreenDialog(
            url = "https://example.com/very/long/path?query=value",
            title = "",
            favicon = null,
            onDismiss = {},
        )
    }
}
