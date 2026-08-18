package net.matsudamper.browser

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 開発者ツールのネットワークログ画面の状態。
 */
@Immutable
internal data class NetworkLogUiState(
    val callbacks: Callbacks,
    /** 絞り込み後のログ。新しい順 */
    val entries: List<Entry>,
    val filters: List<Filter>,
    val searchQuery: String,
    val summary: Summary,
    /** 詳細を開いている場合の内容。一覧表示中は null */
    val detail: Detail?,
) {
    /** 一覧に並ぶ 1 行 */
    @Immutable
    data class Entry(
        val id: String,
        val method: String,
        val statusLabel: String,
        val statusKind: StatusKind,
        val typeLabel: String,
        /** ファイル名等の短い表示名 */
        val name: String,
        val host: String,
        val sizeLabel: String,
        val durationLabel: String,
        val fromCache: Boolean,
    )

    /** ステータスの区分。表示色の出し分けに使う */
    enum class StatusKind {
        Success,
        Redirect,
        ClientError,
        ServerError,
        Failed,
        Pending,
    }

    /** 種別での絞り込みチップ */
    @Immutable
    data class Filter(
        val type: ResourceFilter,
        val label: String,
        val count: Int,
        val isSelected: Boolean,
    )

    /** 一覧上部に出す集計 */
    @Immutable
    data class Summary(
        val countLabel: String,
        val sizeLabel: String,
    )

    /** 詳細表示の内容 */
    @Immutable
    data class Detail(
        val id: String,
        val name: String,
        val url: String,
        val items: List<Item>,
        val requestHeaders: List<Header>,
        val responseHeaders: List<Header>,
        val preview: Preview,
    ) {
        /** 「サイズ」「ステータス」などのラベル付きの値 */
        @Immutable
        data class Item(
            val label: String,
            val value: String,
        )
    }

    @Immutable
    data class Header(
        val name: String,
        val value: String,
    )

    /** レスポンス本文のプレビュー */
    @Immutable
    sealed interface Preview {
        /** 取得中 */
        data object Loading : Preview

        /** 画像として表示できる場合 */
        data class Image(
            val bitmap: ImageBitmap,
            val sizeLabel: String,
        ) : Preview

        /** テキストとして表示できる場合 */
        data class Text(
            val text: String,
            val isTruncated: Boolean,
        ) : Preview

        /** 表示できない場合の理由 */
        data class Unavailable(
            val message: String,
        ) : Preview
    }

    /** 種別の絞り込み条件 */
    enum class ResourceFilter {
        All,
        Document,
        Stylesheet,
        Script,
        Image,
        Media,
        Font,
        Xhr,
        Other,
    }

    interface Callbacks {
        fun onClickFilter(filter: ResourceFilter)
        fun onSearchQueryChange(query: String)
        fun onClickEntry(id: String)
        fun onClickCloseDetail()
        fun onClickCopyUrl()
        fun onClickCopyBody()
        fun onClickReloadPreview()
        fun onClickClear()
        fun onDismiss()
    }
}
