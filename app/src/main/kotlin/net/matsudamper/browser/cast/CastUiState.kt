package net.matsudamper.browser.cast

import androidx.compose.runtime.Stable

/**
 * キャスト機能のUI状態を保持するデータクラス。
 * Composableがこの状態を観測してCastボタンの表示/非表示を切り替える。
 */
@Stable
data class CastUiState(
    // Chromecastデバイスが検出されているか（Google Play Services未対応端末ではfalse固定）
    val isAvailable: Boolean = false,
    // キャストセッションがアクティブか
    val isConnected: Boolean = false,
    // 接続中のデバイス名
    val deviceName: String = "",
    // キャスト可能なメディアURLが存在するか（blob:やdata:は不可）
    val hasMediaSourceUrl: Boolean = false,
)
