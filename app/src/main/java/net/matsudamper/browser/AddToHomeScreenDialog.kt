package net.matsudamper.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/**
 * ホームへの追加方法を選択するダイアログ。
 * ショートカット（ブラウザで開く）とアプリとして追加の2択を提供する。
 * PWAマニフェストがある場合は名前・アイコンをマニフェストから取得する。
 */
@Composable
internal fun AddToHomeScreenDialog(
    url: String,
    title: String,
    favicon: Bitmap?,
    webAppManifest: JSONObject?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // マニフェストの short_name > name > ページタイトル > URL の優先度で表示名を決定
    val displayName = remember(webAppManifest, title, url) {
        webAppManifest?.optString("short_name")?.takeIf { it.isNotBlank() }
            ?: webAppManifest?.optString("name")?.takeIf { it.isNotBlank() }
            ?: title.ifBlank { url }
    }

    // マニフェストの start_url があればそれを使用、なければ現在のURLを使用
    val startUrl = remember(webAppManifest, url) {
        webAppManifest?.optString("start_url")?.takeIf { it.isNotBlank() } ?: url
    }

    // display が standalone / fullscreen / minimal-ui なら PWAとして扱う
    val isPwa = remember(webAppManifest) {
        val display = webAppManifest?.optString("display") ?: ""
        display == "standalone" || display == "fullscreen" || display == "minimal-ui"
    }

    // マニフェストアイコンを非同期でフェッチし、faviconをフォールバックにする
    var resolvedIcon by remember(webAppManifest, favicon) { mutableStateOf<Bitmap?>(favicon) }
    LaunchedEffect(webAppManifest) {
        if (webAppManifest == null) return@LaunchedEffect
        val iconUrl = resolveBestManifestIconUrl(webAppManifest) ?: return@LaunchedEffect
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(iconUrl).openConnection() as java.net.HttpURLConnection
                try {
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.connect()
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
        if (fetched != null) {
            resolvedIcon = fetched
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ホームに追加") },
        text = {
            Column {
                if (isPwa) {
                    Text("PWA")
                }
                Text(displayName)
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
                        addShortcutToHome(context, url, displayName, resolvedIcon)
                        onDismiss()
                    },
                ) {
                    Text("ショートカット")
                }
                TextButton(
                    onClick = {
                        addWebAppToHome(context, startUrl, displayName, resolvedIcon)
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
 * マニフェストのiconsリストから最適なアイコンURLを選択する。
 * 192x192以上の最小サイズを優先し、なければ最大サイズを返す。
 * GeckoViewはmanifestのURLを解決済みの絶対URLで渡す。
 */
private fun resolveBestManifestIconUrl(manifest: JSONObject): String? {
    val icons = manifest.optJSONArray("icons") ?: return null
    var bestUrl: String? = null
    var bestSize = 0
    for (i in 0 until icons.length()) {
        val icon = icons.optJSONObject(i) ?: continue
        val src = icon.optString("src").takeIf { it.isNotBlank() } ?: continue
        // "192x192" や "192x192 512x512" の形式に対応
        val size = icon.optString("sizes")
            .split(" ")
            .firstOrNull()
            ?.split("x")
            ?.firstOrNull()
            ?.toIntOrNull() ?: 0
        if (size >= 192 && (bestSize < 192 || size < bestSize)) {
            // 192以上で最小のものを選ぶ（表示品質と帯域のバランス）
            bestUrl = src
            bestSize = size
        } else if (bestSize < 192 && size > bestSize) {
            // 192未満なら最大のものを選ぶ
            bestUrl = src
            bestSize = size
        }
    }
    return bestUrl
}

/**
 * ホーム画面にショートカットを追加する。
 * ショートカットはアプリの http/https ディープリンクハンドラ経由でURLを開く。
 */
private fun addShortcutToHome(context: Context, url: String, title: String, icon: Bitmap?) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        Toast.makeText(context, "ランチャーがショートカット追加に対応していません", Toast.LENGTH_SHORT).show()
        return
    }
    // DeepLinkActivity を経由することでアプリの http/https VIEW ルーティングを正しく使用する
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url), context, DeepLinkActivity::class.java)
    val shortcutIcon = if (icon != null) {
        IconCompat.createWithBitmap(icon)
    } else {
        IconCompat.createWithResource(context, R.drawable.ic_firefox_like)
    }
    val info = ShortcutInfoCompat.Builder(context, "shortcut_${url.hashCode()}")
        .setShortLabel(title.take(25))
        .setLongLabel(title)
        .setIcon(shortcutIcon)
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.requestPinShortcut(context, info, null)
}

/**
 * ホーム画面にアプリとして追加する。
 * 専用の WebAppActivity で開き、ドキュメントタスクとして独立したRecentsエントリを持つ。
 * PWAマニフェストがある場合は start_url を使用する。
 */
private fun addWebAppToHome(context: Context, url: String, title: String, icon: Bitmap?) {
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
    val shortcutIcon = if (icon != null) {
        IconCompat.createWithBitmap(icon)
    } else {
        IconCompat.createWithResource(context, R.drawable.ic_firefox_like)
    }
    val info = ShortcutInfoCompat.Builder(context, "webapp_${url.hashCode()}")
        .setShortLabel(title.take(25))
        .setLongLabel(title)
        .setIcon(shortcutIcon)
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
            webAppManifest = null,
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
            webAppManifest = null,
            onDismiss = {},
        )
    }
}

@Preview(name = "PWAマニフェストあり")
@Composable
private fun PreviewWithPwaManifest() {
    BrowserTheme(themeMode = net.matsudamper.browser.data.ThemeMode.THEME_SYSTEM) {
        AddToHomeScreenDialog(
            url = "https://example.com/",
            title = "Example",
            favicon = null,
            webAppManifest = JSONObject().apply {
                put("name", "Example PWA App")
                put("short_name", "ExamplePWA")
                put("start_url", "/")
                put("display", "standalone")
            },
            onDismiss = {},
        )
    }
}
