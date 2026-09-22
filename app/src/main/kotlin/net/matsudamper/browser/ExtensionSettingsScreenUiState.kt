package net.matsudamper.browser

import androidx.compose.runtime.Stable
import org.mozilla.geckoview.GeckoSession

/**
 * 拡張機能の設定ページを表示する画面の状態。
 *
 * ページ本体は GeckoView が描画するため、表示する内容としてセッションを渡す。
 */
@Stable
internal data class ExtensionSettingsScreenUiState(
    val extensionName: String,
    val session: GeckoSession,
    val listener: Listener,
) {
    @Stable
    interface Listener {
        /** セッションへ delegate を差し込み終えたことを伝える。初回ロードはここから始める */
        fun onSessionDelegatesAttached()
    }
}
