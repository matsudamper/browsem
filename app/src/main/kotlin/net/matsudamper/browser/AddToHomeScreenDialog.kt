package net.matsudamper.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import net.matsudamper.browser.resources.R as ResourcesR
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
    isIconLoading: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ホームに追加") },
        text = {
            Column {
                Text(title.ifBlank { url })
                if (isIconLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("アイコンを取得中")
                    }
                }
            }
        },
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
                    enabled = !isIconLoading,
                ) {
                    Text("ショートカット")
                }
                TextButton(
                    onClick = {
                        addWebAppToHome(context, url, title, favicon)
                        onDismiss()
                    },
                    enabled = !isIconLoading,
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
        IconCompat.createWithResource(context, ResourcesR.drawable.ic_firefox_like)
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
    // ショートカットと違い、アプリ追加 (FLAG_ACTIVITY_NEW_DOCUMENT + documentLaunchMode)
    // のピンはランチャーが TYPE_BITMAP アイコンを採用しないケースが報告されている
    // (アイコンがアプリのデフォルト ic_firefox_like にフォールバックする)。
    // そのため、明示的に adaptive icon としてラップして渡すことで安定して反映させる。
    val icon = if (favicon != null) {
        LauncherIconFactory.toAdaptiveIconCompat(favicon)
    } else {
        IconCompat.createWithResource(context, ResourcesR.drawable.ic_firefox_like)
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
            isIconLoading = false,
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
            isIconLoading = true,
            onDismiss = {},
        )
    }
}
