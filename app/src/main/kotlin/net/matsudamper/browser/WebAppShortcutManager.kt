package net.matsudamper.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlin.math.max
import net.matsudamper.browser.data.WebAppId

/**
 * ホームに「アプリとして追加」したウェブアプリのピン留めショートカットを管理する。
 */
internal class WebAppShortcutManager(
    private val context: Context,
) {
    fun isPinSupported(): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /**
     * 登録済みのウェブアプリをホームにピン留めする。
     * 専用の WebAppActivity で開き、ドキュメントタスクとして独立したRecentsエントリを持つ。
     * ピン留めがキャンセルされて残った登録は、ウェブアプリ一覧を開いたときに [pinnedWebAppIds] との突き合わせで消える。
     */
    fun requestPin(webAppId: WebAppId, label: String, favicon: Bitmap?) {
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
            .setIntent(createLaunchIntent(webAppId))
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /** 旧形式の起動 URI を持つピン留めショートカットを探す */
    fun findPinnedLegacyShortcut(legacyLaunchUri: String): PinnedLegacyShortcut? {
        val shortcut = pinnedShortcuts().firstOrNull { it.intent.data?.toString() == legacyLaunchUri }
        return if (shortcut == null) {
            null
        } else {
            PinnedLegacyShortcut(
                shortcutId = shortcut.id,
                shortLabel = shortcut.shortLabel.toString(),
                label = (shortcut.longLabel ?: shortcut.shortLabel).toString(),
            )
        }
    }

    /**
     * 旧形式のショートカットを、ウェブアプリ ID で起動する新形式へ書き換える。
     * ピン留めショートカットはショートカット ID を変えられないため、ID は旧形式のまま Intent だけを差し替える。
     * アイコンは指定しなければ既存のものが残る。
     */
    fun migrateLegacyShortcut(shortcut: PinnedLegacyShortcut, webAppId: WebAppId) {
        val info = ShortcutInfoCompat.Builder(context, shortcut.shortcutId)
            .setShortLabel(shortcut.shortLabel)
            .setIntent(createLaunchIntent(webAppId))
            .build()
        ShortcutManagerCompat.updateShortcuts(context, listOf(info))
    }

    /** ホームにピン留めされたままのウェブアプリ。まだ移行していない旧形式のアプリは含まない */
    fun pinnedWebAppIds(): Set<WebAppId> {
        return pinnedShortcuts().mapNotNull { it.webAppIdOrNull() }.toSet()
    }

    /** ホームに残ったアイコンは消せないため、無効化して起動できなくする */
    fun disableShortcut(webAppId: WebAppId) {
        // 旧形式から移行したショートカットは ID が旧形式のままなので、ID ではなく Intent から探す
        val shortcutIds = pinnedShortcuts()
            .filter { it.webAppIdOrNull() == webAppId }
            .map { it.id }
        ShortcutManagerCompat.disableShortcuts(context, shortcutIds, "削除されたアプリです")
    }

    private fun pinnedShortcuts(): List<ShortcutInfoCompat> {
        return ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
    }

    private fun ShortcutInfoCompat.webAppIdOrNull(): WebAppId? {
        return when (val target = WebAppLaunchUri.parse(intent.data)) {
            is WebAppLaunchTarget.Registered -> target.webAppId
            is WebAppLaunchTarget.Legacy, null -> null
        }
    }

    // 独立した Recents エントリは WebAppActivity の documentLaunchMode="intoExisting"
    // (= FLAG_ACTIVITY_NEW_DOCUMENT 相当) が保証するため、ピン Intent 側にフラグは不要。
    private fun createLaunchIntent(webAppId: WebAppId): Intent {
        return Intent(context, WebAppActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = WebAppLaunchUri.create(webAppId)
        }
    }

    private fun WebAppId.toShortcutId(): String = "$SHORTCUT_ID_PREFIX$value"

    private companion object {
        private const val SHORTCUT_ID_PREFIX = "web_app_"
    }
}

internal data class PinnedLegacyShortcut(
    val shortcutId: String,
    val shortLabel: String,
    val label: String,
)

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
