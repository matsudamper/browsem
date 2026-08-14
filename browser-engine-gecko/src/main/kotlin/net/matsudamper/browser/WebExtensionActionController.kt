package net.matsudamper.browser

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.Image
import org.mozilla.geckoview.WebExtension

/**
 * 拡張機能の browserAction / pageAction をタブ (GeckoSession) 単位で保持し、
 * ツールバーメニューへアイコンとして公開するコントローラ。
 *
 * GeckoView はアクションを
 * - 全タブ共通のデフォルト (WebExtension#setActionDelegate)
 * - タブ固有の上書き (GeckoSession#webExtensionController#setActionDelegate)
 * の 2 系統で通知してくるため、両方を保持して [WebExtension.Action.withDefault] でマージする。
 * どちらの delegate も登録しないと拡張機能側の browserAction API がタブに反映されない。
 */
@Stable
class WebExtensionActionController(private val runtime: GeckoRuntime) {

    /** メニューに 1 アイコンとして表示する拡張機能アクション */
    @Stable
    data class ActionUiState(
        val extensionId: String,
        val title: String,
        val icon: Bitmap?,
        val badgeText: String?,
    )

    /** 拡張機能のポップアップ (browser_action の default_popup) の表示要求 */
    @Stable
    class PopupRequest(
        val extensionId: String,
        val title: String,
        val session: GeckoSession,
    )

    private data class ActionEntry(
        val browserAction: WebExtension.Action? = null,
        val pageAction: WebExtension.Action? = null,
    )

    private enum class ActionType { BROWSER, PAGE }

    private data class ResolvedAction(
        val action: WebExtension.Action,
        val type: ActionType,
    )

    /**
     * アイコンの保存先。[session] が null なら全タブ共通の既定アイコンを表す。
     * browserAction と pageAction はアイコンが異なりうるため [type] も含める。
     */
    private data class IconSlot(
        val session: GeckoSession?,
        val extensionId: String,
        val type: ActionType,
    )

    // 対象の拡張機能。UI から参照するため観測可能にする
    private val extensions = mutableStateMapOf<String, WebExtension>()

    // 全タブ共通のアクション
    private val defaultActions = mutableStateMapOf<String, ActionEntry>()

    // タブごとのアクション上書き
    private val sessionActions = mutableStateMapOf<GeckoSession, SnapshotStateMap<String, ActionEntry>>()

    // 最後にデコードできたアイコン。Image は通知のたびに作り直されるため、
    // Image ではなく「どのタブのどのアクションのアイコンか」をキーにする
    private val iconBitmaps = mutableStateMapOf<IconSlot, Bitmap>()

    // 各スロットで最後にデコードを要求した Image。古いデコード結果で上書きしないための目印
    private val pendingIcons = mutableMapOf<IconSlot, Image>()

    // 同一内容の Bitmap を共有するための正規化テーブル。
    // ほとんどのタブは同じアイコンを表示するため、これでタブ数分の複製を避ける
    private val canonicalBitmaps = mutableListOf<Bitmap>()

    // セッションごとのポップアップ表示コールバック。null 通知は非表示を表す
    private val popupCallbacks = mutableMapOf<GeckoSession, (PopupRequest?) -> Unit>()

    // 現在ポップアップを表示中のセッションと、その表示に使っているセッション
    private val openPopupSessions = mutableMapOf<GeckoSession, GeckoSession>()

    // click() を呼んだタブ。ポップアップの表示要求はこのタブへ振り分ける
    private var pendingPopupOwner: GeckoSession? = null

    private val actionDelegate = object : WebExtension.ActionDelegate {
        override fun onBrowserAction(
            extension: WebExtension,
            session: GeckoSession?,
            action: WebExtension.Action,
        ) {
            requestIcon(IconSlot(session, extension.id, ActionType.BROWSER), action.icon)
            updateEntry(extension, session) { it.copy(browserAction = action) }
        }

        override fun onPageAction(
            extension: WebExtension,
            session: GeckoSession?,
            action: WebExtension.Action,
        ) {
            requestIcon(IconSlot(session, extension.id, ActionType.PAGE), action.icon)
            updateEntry(extension, session) { it.copy(pageAction = action) }
        }

        override fun onTogglePopup(
            extension: WebExtension,
            action: WebExtension.Action,
        ): GeckoResult<GeckoSession>? {
            return createPopupSession(extension, action)
        }

        override fun onOpenPopup(
            extension: WebExtension,
            action: WebExtension.Action,
        ): GeckoResult<GeckoSession>? {
            return createPopupSession(extension, action)
        }
    }

    /**
     * 拡張機能を対象に加え、デフォルトアクションの delegate を登録する。
     * インストール済みの拡張機能・新規に ready になった拡張機能の双方から呼ばれる。
     */
    fun attachExtension(extension: WebExtension) {
        // ビルトイン拡張機能 (ThemeColor/Media 等) は browserAction を持たないため対象外
        if (extension.isBuiltIn) return
        extensions[extension.id] = extension
        extension.setActionDelegate(actionDelegate)
        popupCallbacks.keys.forEach { session ->
            session.webExtensionController.setActionDelegate(extension, actionDelegate)
        }
    }

    /**
     * タブのセッションを登録し、タブ固有アクションの delegate を張る。
     * [onPopupChanged] はポップアップを開くときに [PopupRequest]、
     * 拡張機能側から閉じられたときに null で呼ばれる。
     */
    fun registerSession(session: GeckoSession, onPopupChanged: (PopupRequest?) -> Unit) {
        popupCallbacks[session] = onPopupChanged
        sessionActions.getOrPut(session) { mutableStateMapOf() }
        extensions.values.forEach { extension ->
            session.webExtensionController.setActionDelegate(extension, actionDelegate)
        }
    }

    fun unregisterSession(session: GeckoSession) {
        popupCallbacks.remove(session)
        sessionActions.remove(session)
        // タブ固有アイコンのキャッシュも一緒に破棄する
        iconBitmaps.keys.filter { it.session === session }.forEach { iconBitmaps.remove(it) }
        pendingIcons.keys.filter { it.session === session }.forEach { pendingIcons.remove(it) }
        closePopup(session)
        extensions.values.forEach { extension ->
            session.webExtensionController.setActionDelegate(extension, null)
        }
    }

    /**
     * タブに対して有効なアクションの一覧。
     * pageAction はタブで show() されたときのみ enabled になるため、
     * 「そのタブに対して機能を持つ拡張機能」だけが並ぶ。
     */
    fun actions(session: GeckoSession): List<ActionUiState> {
        val overrides = sessionActions[session]
        return extensions.values.mapNotNull { extension ->
            val resolved = resolveAction(extension.id, overrides) ?: return@mapNotNull null
            val action = resolved.action
            // enabled は未指定 (null) のとき有効扱い。pageAction は show() 済みのみ有効
            if (action.enabled == false) return@mapNotNull null
            ActionUiState(
                extensionId = extension.id,
                title = action.title?.takeIf { it.isNotBlank() }
                    ?: extension.metaData.name?.takeIf { it.isNotBlank() }
                    ?: extension.id,
                icon = iconBitmap(session, extension.id, resolved.type),
                badgeText = action.badgeText?.takeIf { it.isNotBlank() },
            )
        }.sortedBy { it.title.lowercase() }
    }

    /**
     * 表示するアイコンを、タブ固有 → 全タブ共通の順で解決する。
     *
     * ページ遷移やバッジ更新のたびに GeckoView は新しい [Image] を作り直して通知してくるため、
     * 新しい Image のデコードが終わるまでは直前にデコードできた Bitmap を出し続ける。
     * これをしないと、デコードの間だけアイコンが未取得 (null) となり、
     * 代替アイコンとの間でちらつきが起きる。
     */
    private fun iconBitmap(
        session: GeckoSession,
        extensionId: String,
        type: ActionType,
    ): Bitmap? {
        // タブ固有アイコン (setIcon({tabId})) を他のタブへ流用しないよう、
        // タブのスロットを優先し、無い場合のみ全タブ共通のアイコンへ落とす
        return iconBitmaps[IconSlot(session, extensionId, type)]
            ?: iconBitmaps[IconSlot(null, extensionId, type)]
    }

    /** アイコンのクリック。ポップアップを持つ拡張機能は [PopupRequest] が通知される */
    fun click(session: GeckoSession, extensionId: String) {
        val action = resolveAction(extensionId, sessionActions[session])?.action ?: return
        pendingPopupOwner = session
        runCatching { action.click() }
            .onFailure { error -> Log.w(TAG, "拡張機能アクションのクリックに失敗: $extensionId", error) }
    }

    /** タブのポップアップを閉じ、表示に使っていたセッションを破棄する */
    fun closePopup(session: GeckoSession) {
        val popupSession = openPopupSessions.remove(session) ?: return
        runCatching { popupSession.close() }
            .onFailure { error -> Log.w(TAG, "ポップアップセッションの破棄に失敗", error) }
    }

    private fun createPopupSession(
        extension: WebExtension,
        action: WebExtension.Action,
    ): GeckoResult<GeckoSession>? {
        val owner = pendingPopupOwner ?: return null
        pendingPopupOwner = null
        val callback = popupCallbacks[owner] ?: return null
        // トグル要求で既に開いている場合は閉じるだけにする
        if (openPopupSessions.containsKey(owner)) {
            closePopup(owner)
            return null
        }
        val popupSession = GeckoSession()
        popupSession.open(runtime)
        popupSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) {
                // 拡張機能が window.close() したときはダイアログごと閉じる
                closePopup(owner)
                callback(null)
            }
        }
        openPopupSessions[owner] = popupSession
        callback(
            PopupRequest(
                extensionId = extension.id,
                title = action.title?.takeIf { it.isNotBlank() }
                    ?: extension.metaData.name?.takeIf { it.isNotBlank() }
                    ?: extension.id,
                session = popupSession,
            ),
        )
        return GeckoResult.fromValue(popupSession)
    }

    private fun resolveAction(
        extensionId: String,
        overrides: SnapshotStateMap<String, ActionEntry>?,
    ): ResolvedAction? {
        val default = defaultActions[extensionId]
        val override = overrides?.get(extensionId)
        merge(override?.browserAction, default?.browserAction)?.let {
            return ResolvedAction(it, ActionType.BROWSER)
        }
        return merge(override?.pageAction, default?.pageAction)?.let {
            ResolvedAction(it, ActionType.PAGE)
        }
    }

    private fun merge(
        sessionAction: WebExtension.Action?,
        defaultAction: WebExtension.Action?,
    ): WebExtension.Action? {
        return when {
            sessionAction != null && defaultAction != null -> sessionAction.withDefault(defaultAction)
            else -> sessionAction ?: defaultAction
        }
    }

    private fun updateEntry(
        extension: WebExtension,
        session: GeckoSession?,
        transform: (ActionEntry) -> ActionEntry,
    ) {
        if (extension.isBuiltIn) return
        // アクションだけが先に届く拡張機能でもメニューへ出せるよう、ここでも登録しておく
        if (!extensions.containsKey(extension.id)) {
            extensions[extension.id] = extension
        }
        val target = if (session == null) {
            defaultActions
        } else {
            sessionActions.getOrPut(session) { mutableStateMapOf() }
        }
        target[extension.id] = transform(target[extension.id] ?: ActionEntry())
    }

    private fun requestIcon(slot: IconSlot, image: Image?) {
        if (image == null) return
        // 同じ Image の再通知なら、既に走らせたデコードの結果をそのまま使う
        if (pendingIcons[slot] === image) return
        pendingIcons[slot] = image
        image.getBitmap(ICON_SIZE_PX).accept(
            { bitmap ->
                // デコード中に新しい通知が来ていた場合、古い結果で上書きしない
                if (bitmap != null && pendingIcons[slot] === image) {
                    iconBitmaps[slot] = canonicalize(bitmap)
                }
            },
            { error ->
                if (pendingIcons[slot] === image) {
                    pendingIcons.remove(slot)
                }
                Log.w(TAG, "拡張機能アイコンの取得に失敗", error)
            },
        )
    }

    /**
     * 同じ見た目の Bitmap を 1 インスタンスに寄せる。
     * タブごとにアイコンを保持するとタブ数分だけ複製されてしまうため、
     * 内容が一致するものは既存のインスタンスを共有する。
     * インスタンスが変わらなければ UiState の再生成による再コンポーズも避けられる。
     */
    private fun canonicalize(bitmap: Bitmap): Bitmap {
        canonicalBitmaps.firstOrNull { it.sameAs(bitmap) }?.let { return it }
        // アイコンをアニメーションさせる拡張機能で無限に増えないよう上限を設ける
        if (canonicalBitmaps.size < MAX_CANONICAL_BITMAPS) {
            canonicalBitmaps.add(bitmap)
        }
        return bitmap
    }

    companion object {
        private const val TAG = "WebExtensionAction"
        private const val ICON_SIZE_PX = 96

        /** 正規化テーブルに保持するアイコンの上限 */
        private const val MAX_CANONICAL_BITMAPS = 64
    }
}
