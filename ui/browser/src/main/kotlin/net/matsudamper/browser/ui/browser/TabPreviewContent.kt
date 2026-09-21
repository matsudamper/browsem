package net.matsudamper.browser.ui.browser

import androidx.compose.runtime.Stable

/**
 * タブ切替スワイプ中に表示する隣接タブの内容。
 * 値ではなくプロパティで公開することで、読み出し元のタブが持つ Compose の状態変化をそのまま追従できる。
 */
@Stable
interface TabPreviewContent {
    val title: String
    val currentUrl: String
    val themeColor: Int?
    val previewImage: ByteArray?
}
