package net.matsudamper.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import kotlin.math.max
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
    // 独立した Recents エントリは WebAppActivity の documentLaunchMode="intoExisting"
    // (= FLAG_ACTIVITY_NEW_DOCUMENT 相当) が保証するため、ピン Intent 側にフラグは不要。
    val intent = Intent(context, WebAppActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse(url)
    }
    // documentLaunchMode のアプリピンは、ランチャーがアイコンの透過部分を黒で塗りつぶし、
    // 暗い favicon と合わさって真っ黒に見える。透過を不透明な白背景で埋めてから渡す。
    val icon = if (favicon != null) {
        IconCompat.createWithBitmap(favicon.toOpaqueSquareIcon())
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

/**
 * favicon を不透明な白背景の正方形 Bitmap に合成する。
 * documentLaunchMode のアプリピンではランチャーがアイコンの透過部分を黒で塗るため、
 * 透過を白で埋めて真っ黒化を防ぐ。元 Bitmap が長方形でも短辺側を余白とした正方形にする。
 */
private fun Bitmap.toOpaqueSquareIcon(): Bitmap {
    val size = max(width, height)
    val squared = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(squared)
    canvas.drawColor(Color.WHITE)
    // 元画像を中央に配置する
    val left = (size - width) / 2f
    val top = (size - height) / 2f
    canvas.drawBitmap(this, left, top, null)
    return squared
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
