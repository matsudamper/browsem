package net.matsudamper.browser

import android.content.Context
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

    // 登録済みウェブアプリをホームへ再ピン留めする。アイコンは指定せずランチャーが既存のものを引き継ぐ。
    fun requestPinRegisteredWebApp(launchInfo: WebAppLaunchInfo, label: String): Boolean {
        if (!isPinSupported()) return false
        val resolvedLabel = label.ifBlank { launchInfo.startUrl }
        val info = ShortcutInfoCompat.Builder(context, launchInfo.webAppId.toShortcutId())
            .setShortLabel(resolvedLabel.take(25))
            .setLongLabel(resolvedLabel)
            .setIntent(WebAppLaunchUri.createIntent(context, launchInfo))
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, info, null)
        return true
    }

    /**
     * 登録済みのウェブアプリをホームにピン留めする。
     * 専用の WebAppActivity で開き、ドキュメントタスクとして独立したRecentsエントリを持つ。
     * ピン留めがキャンセルされて残った登録は、ウェブアプリ一覧を開いたときに [pinnedWebAppIds] との突き合わせで消える。
     */
    fun requestPin(launchInfo: WebAppLaunchInfo, label: String, favicon: Bitmap?) {
        // documentLaunchMode のアプリピンは、ランチャーがアイコンの透過部分を黒で塗りつぶし、
        // 暗い favicon と合わさって真っ黒に見える。透過を不透明な白背景で埋めてから渡す。
        val icon = if (favicon != null) {
            IconCompat.createWithBitmap(favicon.toOpaqueSquareIcon())
        } else {
            IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        }
        val info = ShortcutInfoCompat.Builder(context, launchInfo.webAppId.toShortcutId())
            .setShortLabel(label.take(25))
            .setLongLabel(label)
            .setIcon(icon)
            .setIntent(WebAppLaunchUri.createIntent(context, launchInfo))
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /**
     * 起動 URI が一致するピン留めショートカットを探す。
     * 同じ URL を通常のショートカット（DeepLinkActivity 向け）としても追加していると旧形式の data URI が一致するため、起動先でも絞る。
     * 削除して無効化したアイコンもホームに残るため、無効化済みのものは対象にしない。
     */
    fun findPinnedShortcut(launchUri: String): PinnedWebAppShortcut? {
        val shortcut = pinnedShortcuts().firstOrNull {
            it.isEnabled &&
                it.intent.component?.className == WebAppActivity::class.java.name &&
                it.intent.data?.toString() == launchUri
        }
        return if (shortcut == null) {
            null
        } else {
            PinnedWebAppShortcut(
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
     * @return ランチャーがショートカットを書き換えたか。レート制限などで拒否されると false
     */
    fun migrateLegacyShortcut(shortcut: PinnedWebAppShortcut, launchInfo: WebAppLaunchInfo): Boolean {
        val info = ShortcutInfoCompat.Builder(context, shortcut.shortcutId)
            .setShortLabel(shortcut.shortLabel)
            .setIntent(WebAppLaunchUri.createIntent(context, launchInfo))
            .build()
        return ShortcutManagerCompat.updateShortcuts(context, listOf(info))
    }

    /** ホームにピン留めされたままのウェブアプリ。まだ移行していない旧形式のアプリは含まない */
    fun pinnedWebAppIds(): Set<WebAppId> {
        return pinnedShortcuts().mapNotNull { it.webAppIdOrNull() }.toSet()
    }

    /**
     * ホームのアイコンはアプリから消せないため、削除済みとわかるラベルに変えてから無効化し、起動できなくする。
     * 無効化したショートカットは更新できないので、ラベルの変更を先に行う。
     * @return ラベルの変更がレート制限などで拒否されると、元の名前のまま起動できなくなるのを避けるため無効化せず false を返す
     */
    fun disableShortcut(webAppId: WebAppId, title: String): Boolean {
        val shortcuts = pinnedShortcuts(webAppId)
        if (!updateLabel(shortcuts = shortcuts, label = "(削除済み) $title")) {
            return false
        }
        ShortcutManagerCompat.disableShortcuts(
            context,
            shortcuts.map { it.id },
            "削除されたアプリです。アイコンを長押ししてホームから削除してください",
        )
        return true
    }

    /** ホームのアイコンのラベルを変える。レート制限などでランチャーに拒否されると false */
    fun updateLabel(webAppId: WebAppId, label: String): Boolean {
        return updateLabel(shortcuts = pinnedShortcuts(webAppId), label = label)
    }

    // 旧形式から移行したショートカットは ID が旧形式のままなので、ID ではなく Intent から探す
    private fun pinnedShortcuts(webAppId: WebAppId): List<ShortcutInfoCompat> {
        return pinnedShortcuts().filter { it.webAppIdOrNull() == webAppId }
    }

    /** Intent はそのまま引き継ぐ。アイコンは指定しなければ既存のものが残る */
    private fun updateLabel(shortcuts: List<ShortcutInfoCompat>, label: String): Boolean {
        val infos = shortcuts.map { shortcut ->
            ShortcutInfoCompat.Builder(context, shortcut.id)
                .setShortLabel(label.take(25))
                .setLongLabel(label)
                .setIntent(shortcut.intent)
                .build()
        }
        return ShortcutManagerCompat.updateShortcuts(context, infos)
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

    private fun WebAppId.toShortcutId(): String = "$SHORTCUT_ID_PREFIX$value"

    private companion object {
        private const val SHORTCUT_ID_PREFIX = "web_app_"
    }
}

internal data class PinnedWebAppShortcut(
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
