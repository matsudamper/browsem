package net.matsudamper.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.ProfileId
import net.matsudamper.browser.data.WebAppId
import net.matsudamper.browser.data.WebAppRepository

/**
 * ホームに「アプリとして追加」したウェブアプリのピン留めショートカットを管理する。
 */
internal class WebAppShortcutManager(
    private val context: Context,
    private val webAppRepository: WebAppRepository,
    private val applicationScope: CoroutineScope,
) {
    /**
     * ウェブアプリを登録してホームにピン留めする。
     * 専用の WebAppActivity で開き、ドキュメントタスクとして独立したRecentsエントリを持つ。
     * ピン留めがキャンセルされて残った登録は、ウェブアプリ一覧を開いたときに [pinnedWebAppIds] との突き合わせで消える。
     */
    fun isPinSupported(): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    fun addToHome(url: String, title: String, favicon: Bitmap?, profileId: ProfileId) {
        if (!isPinSupported()) {
            Toast.makeText(context, "ランチャーがショートカット追加に対応していません", Toast.LENGTH_SHORT).show()
            return
        }
        applicationScope.launch {
            val label = title.ifBlank { url }
            val webAppId = webAppRepository.addWebApp(profileId = profileId, startUrl = url, title = label)
            // 独立した Recents エントリは WebAppActivity の documentLaunchMode="intoExisting"
            // (= FLAG_ACTIVITY_NEW_DOCUMENT 相当) が保証するため、ピン Intent 側にフラグは不要。
            val intent = Intent(context, WebAppActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = WebAppLaunchUri.create(webAppId)
            }
            // documentLaunchMode のアプリピンは、ランチャーがアイコンの透過部分を黒で塗りつぶし、
            // 暗い favicon と合わさって真っ黒に見える。透過を不透明な白背景で埋めてから渡す。
            val icon = if (favicon != null) {
                IconCompat.createWithBitmap(favicon.toOpaqueSquareIcon())
            } else {
                IconCompat.createWithResource(context, R.mipmap.ic_launcher)
            }
            val info = ShortcutInfoCompat.Builder(context, webAppId.toShortcutId())
                .setShortLabel(label.take(25))
                .setLongLabel(label)
                .setIcon(icon)
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.requestPinShortcut(context, info, null)
        }
    }

    /** ホームにピン留めされたままのウェブアプリ。DB 管理導入前に追加されたアプリは含まない */
    fun pinnedWebAppIds(): Set<WebAppId> {
        return ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .map { it.id }
            .filter { it.startsWith(SHORTCUT_ID_PREFIX) }
            .map { WebAppId(it.removePrefix(SHORTCUT_ID_PREFIX)) }
            .toSet()
    }

    /** ホームに残ったアイコンは消せないため、無効化して起動できなくする */
    fun disableShortcut(webAppId: WebAppId) {
        ShortcutManagerCompat.disableShortcuts(
            context,
            listOf(webAppId.toShortcutId()),
            "削除されたアプリです",
        )
    }

    private fun WebAppId.toShortcutId(): String = "$SHORTCUT_ID_PREFIX$value"

    private companion object {
        // DB 管理導入前のショートカット ID は "webapp_" で始まるため、区別できる別の接頭辞にする
        private const val SHORTCUT_ID_PREFIX = "web_app_"
    }
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
    val left = (size - width) / 2f
    val top = (size - height) / 2f
    canvas.drawBitmap(this, left, top, null)
    return squared
}
