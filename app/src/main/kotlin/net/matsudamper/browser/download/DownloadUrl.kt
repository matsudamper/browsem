package net.matsudamper.browser.download

import java.util.Locale

/** ダウンロード対象URLの性質を判定するユーティリティ。Android非依存でJVMテスト可能 */
object DownloadUrl {

    /**
     * URLを再取得（GET し直し）できるかどうかを判定する。
     *
     * blob: URL は生成したドキュメント内でのみ有効なオブジェクトURLであり、
     * ページを離れたり別プロセスから取得したりすることはできない。
     * そのため onExternalResponse で受け取った元レスポンスのボディを使い切った後は、
     * 同じURLからデータを取り直すことができない。
     *
     * 例: Google Fonts (Material Symbols) の SVG ダウンロードは
     * `blob:null/<uuid>` 形式のURLでダウンロードが要求される。
     */
    fun isRefetchable(url: String): Boolean {
        return scheme(url) != BLOB_SCHEME
    }

    /** URLのスキームを小文字で返す。スキームを判別できない場合は null */
    private fun scheme(url: String): String? {
        val separatorIndex = url.indexOf(':')
        if (separatorIndex <= 0) return null
        return url.substring(0, separatorIndex).lowercase(Locale.US)
    }

    private const val BLOB_SCHEME = "blob"
}
