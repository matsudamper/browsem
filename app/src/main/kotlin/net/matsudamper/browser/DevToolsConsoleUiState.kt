package net.matsudamper.browser

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * 開発者ツールのコンソール画面の状態。
 * ページの console 出力と、実行した JavaScript の入力・結果を同じ一覧に並べる。
 */
@Immutable
internal data class DevToolsConsoleUiState(
    val callbacks: Callbacks,
    /** 一覧。古い順 */
    val entries: List<Entry>,
    val scriptText: String,
    /** 実行できるかどうか。入力が空の間や実行中は実行できない */
    val canExecute: Boolean,
    /** 実行結果を待っているかどうか */
    val isExecuting: Boolean,
    /** 詳細を開いている場合の内容。一覧表示中は null */
    val detail: Detail?,
) {
    /** 一覧に並ぶ 1 行 */
    @Immutable
    data class Entry(
        val id: Long,
        val kind: Kind,
        /** 行頭に出す種別のラベル */
        val label: String,
        val message: String,
        /** 出力元のページ URL。表示しない行では null */
        val url: String?,
        val listener: Listener,
    ) {
        @Stable
        interface Listener {
            fun onClick()
        }
    }

    /** 行の種別。表示色の出し分けに使う */
    enum class Kind {
        Log,
        Info,
        Warn,
        Error,
        Debug,
        Input,
        Result,
        ResultError,
    }

    /** 全文表示の内容 */
    @Immutable
    data class Detail(
        val title: String,
        val message: String,
        /** 出力元のページ URL。表示しない場合は null */
        val url: String?,
    )

    interface Callbacks {
        fun onScriptTextChange(text: String)
        fun onClickExecute()
        fun onClickClear()
        fun onClickCloseDetail()

        /** 詳細の全文をクリップボードにコピーする */
        fun onClickCopyDetail()
        fun onDismiss()
    }
}
