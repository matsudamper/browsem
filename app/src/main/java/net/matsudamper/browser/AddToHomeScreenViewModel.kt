package net.matsudamper.browser

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/**
 * ホームへの追加ダイアログのViewModel。
 * マニフェスト解析・アイコンフェッチ・ショートカット作成ロジックを担当する。
 */
class AddToHomeScreenViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        /** ホームに表示する名前（マニフェストのshort_name/name優先） */
        val displayName: String = "",
        /** PWAとして追加する際のstart_url */
        val startUrl: String = "",
        /** display が standalone/fullscreen/minimal-ui の場合true */
        val isPwa: Boolean = false,
        /** ショートカットに使うアイコン（マニフェストアイコン優先、faviconフォールバック） */
        val icon: Bitmap? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * ダイアログ表示前に呼び出してマニフェスト情報とアイコンを準備する。
     * マニフェストアイコンの取得は非同期で実行される。
     */
    fun prepare(url: String, title: String, favicon: Bitmap?, manifest: JSONObject?) {
        val displayName = manifest?.optString("short_name")?.takeIf { it.isNotBlank() }
            ?: manifest?.optString("name")?.takeIf { it.isNotBlank() }
            ?: title.ifBlank { url }
        val startUrl = manifest?.optString("start_url")?.takeIf { it.isNotBlank() } ?: url
        val isPwa = manifest?.let {
            val display = it.optString("display")
            display == "standalone" || display == "fullscreen" || display == "minimal-ui"
        } ?: false

        _uiState.value = UiState(
            displayName = displayName,
            startUrl = startUrl,
            isPwa = isPwa,
            icon = favicon,
        )

        if (manifest != null) {
            viewModelScope.launch {
                fetchManifestIcon(manifest, favicon)
            }
        }
    }

    /** ホーム画面にブラウザで開くショートカットを追加する */
    fun addShortcutToHome(pageUrl: String) {
        val context = getApplication<Application>()
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, "ランチャーがショートカット追加に対応していません", Toast.LENGTH_SHORT).show()
            return
        }
        val state = _uiState.value
        // DeepLinkActivity 経由でブラウザとして開く
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl), context, DeepLinkActivity::class.java)
        val info = ShortcutInfoCompat.Builder(context, "shortcut_${pageUrl.hashCode()}")
            .setShortLabel(state.displayName.take(25))
            .setLongLabel(state.displayName)
            .setIcon(state.icon.toIconCompat(context))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /**
     * ホーム画面にTWA（Trusted Web Activity）として追加する。
     * PwaShortcutLauncherActivityを経由することでTWA対応ブラウザ（Chrome等）で起動される。
     * デジタルアセットリンクで検証済みならURLバーなし、未検証でもアプリ風に表示される。
     */
    fun addWebAppToHome() {
        val context = getApplication<Application>()
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, "ランチャーがショートカット追加に対応していません", Toast.LENGTH_SHORT).show()
            return
        }
        val state = _uiState.value
        // PwaShortcutLauncherActivity（LauncherActivity継承）経由でTWA起動
        // FLAG_ACTIVITY_NEW_DOCUMENT で独立したRecentsエントリを持つ
        val intent = Intent(context, PwaShortcutLauncherActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(state.startUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
        val info = ShortcutInfoCompat.Builder(context, "webapp_${state.startUrl.hashCode()}")
            .setShortLabel(state.displayName.take(25))
            .setLongLabel(state.displayName)
            .setIcon(state.icon.toIconCompat(context))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /**
     * マニフェストのiconsリストから最適なアイコンを非同期でフェッチする。
     * 192x192以上の最小サイズを優先し、なければ最大サイズを使用する。
     * フェッチ成功時はアイコンを更新し、失敗時はfaviconのままにする。
     */
    private suspend fun fetchManifestIcon(manifest: JSONObject, favicon: Bitmap?) {
        val iconUrl = resolveBestManifestIconUrl(manifest) ?: return
        val bitmap = withContext(Dispatchers.IO) {
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
        _uiState.update { it.copy(icon = bitmap ?: favicon) }
    }

    private fun resolveBestManifestIconUrl(manifest: JSONObject): String? {
        val icons = manifest.optJSONArray("icons") ?: return null
        var bestUrl: String? = null
        var bestSize = 0
        for (i in 0 until icons.length()) {
            val icon = icons.optJSONObject(i) ?: continue
            val src = icon.optString("src").takeIf { it.isNotBlank() } ?: continue
            val size = icon.optString("sizes")
                .split(" ")
                .firstOrNull()
                ?.split("x")
                ?.firstOrNull()
                ?.toIntOrNull() ?: 0
            if (size >= 192 && (bestSize < 192 || size < bestSize)) {
                // 192以上で最小のものを選ぶ
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

    private fun Bitmap?.toIconCompat(context: android.content.Context): IconCompat {
        return if (this != null) {
            IconCompat.createWithBitmap(this)
        } else {
            IconCompat.createWithResource(context, R.drawable.ic_firefox_like)
        }
    }
}
