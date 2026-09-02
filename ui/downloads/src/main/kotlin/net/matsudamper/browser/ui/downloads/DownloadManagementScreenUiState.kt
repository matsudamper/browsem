package net.matsudamper.browser.ui.downloads

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import java.util.UUID

@Stable
data class DownloadManagementScreenUiState(
    val isLoading: Boolean,
    val downloads: List<DownloadItem>,
    /** 履歴クリア対象のレコードがあるか。実行中のみの場合は false */
    val hasClearableHistory: Boolean,
    val showClearHistoryDialog: Boolean,
    val callbacks: Callbacks,
) {
    @Stable
    sealed interface DownloadStatus {
        data class InProgress(
            val progress: Int,
            val totalRead: Long,
            val contentLength: Long,
            val isIndeterminate: Boolean,
        ) : DownloadStatus

        /**
         * ダウンロード完了。
         */
        data class Completed(
            val fileUri: String,
        ) : DownloadStatus

        /**
         * ダウンロード失敗。
         * [canResume] が true の場合は再開ボタンを表示する。
         * [reason] は失敗原因の説明。取得できていない場合は null
         */
        data class Failed(
            val canResume: Boolean,
            val reason: String?,
        ) : DownloadStatus

        data object Cancelled : DownloadStatus

        /**
         * ダウンロード一時停止中。
         * [canResume] が true の場合は再開ボタンを表示する
         */
        data class Paused(
            val progress: Int,
            val totalRead: Long,
            val contentLength: Long,
            val canResume: Boolean,
        ) : DownloadStatus
    }

    /**
     * ダウンロード完了アイテムの左側に表示するプレビュー。
     * 種類によって表示方法（クロップ / 全体表示 / アイコン描画）が異なる
     */
    @Stable
    sealed interface Preview {
        /** 画像・動画などのサムネイル。表示領域いっぱいにクロップして表示する */
        data class Thumbnail(val image: ImageBitmap) : Preview

        /** APK から取り出したアプリアイコン。全体が収まるように表示する */
        data class AppIcon(val image: ImageBitmap) : Preview

        /** サムネイルを取得できないファイルに MIME タイプから割り当てる汎用アイコン */
        data class FileType(val fileType: DownloadFileType) : Preview
    }

    /** MIME タイプから判定したファイル種別。汎用アイコンの出し分けに使う */
    enum class DownloadFileType {
        /** zip・gzip・tar などの圧縮アーカイブ */
        ARCHIVE,
        PDF,
        VIDEO,
        AUDIO,

        /** 上記のいずれにも当てはまらないファイル */
        UNKNOWN,
    }

    @Stable
    data class DownloadItem(
        val id: UUID,
        val fileName: String,
        val status: DownloadStatus,
        val enqueuedAt: Long,
        /** ダウンロード開始時に表示していたページのURL。不明な場合は null */
        val originPageUrl: String?,
        val listener: Listener,
    ) {
        @Stable
        interface Listener {
            fun onCancel()

            /** ダウンロード中のアイテムを一時停止する */
            fun onPause()
            fun onOpenFile()

            /** 失敗したダウンロードを再開する */
            fun onResume()

            /** ダウンロード開始時のページを新しいタブで開く。originPageUrl が null の場合は no-op */
            fun onOpenOriginPage()
        }
    }

    interface Callbacks {
        fun onOpenDownloadsFolder()

        fun onClickClearHistory()

        fun onConfirmClearHistory()

        fun onDismissClearHistoryDialog()

        /** ファイル URI からプレビューを読み込む。サムネイルを取得できない場合は汎用アイコンを返す */
        suspend fun loadPreview(fileUri: String): Preview
    }
}

internal object PreviewDownloadManagementCallbacks : DownloadManagementScreenUiState.Callbacks {
    override fun onOpenDownloadsFolder() = Unit
    override fun onClickClearHistory() = Unit
    override fun onConfirmClearHistory() = Unit
    override fun onDismissClearHistoryDialog() = Unit
    override suspend fun loadPreview(fileUri: String): DownloadManagementScreenUiState.Preview {
        return DownloadManagementScreenUiState.Preview.FileType(
            DownloadManagementScreenUiState.DownloadFileType.UNKNOWN,
        )
    }
}

internal object PreviewDownloadItemListener : DownloadManagementScreenUiState.DownloadItem.Listener {
    override fun onCancel() = Unit
    override fun onPause() = Unit
    override fun onOpenFile() = Unit
    override fun onResume() = Unit
    override fun onOpenOriginPage() = Unit
}
