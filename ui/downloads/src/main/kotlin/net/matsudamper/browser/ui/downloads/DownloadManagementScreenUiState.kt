package net.matsudamper.browser.ui.downloads

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import java.util.UUID

@Stable
data class DownloadManagementScreenUiState(
    val isLoading: Boolean,
    val downloads: List<DownloadItem>,
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
         * [thumbnail] はサムネイルを生成できるファイル (画像・動画など) の場合のみ非 null。
         */
        data class Completed(
            val fileUri: String,
            val thumbnail: ImageBitmap?,
        ) : DownloadStatus

        /**
         * ダウンロード失敗。
         * [canResume] が true の場合は再開ボタンを表示する。
         */
        data class Failed(val canResume: Boolean) : DownloadStatus

        data object Cancelled : DownloadStatus

        /**
         * ダウンロード一時停止中。再開ボタンを表示する。
         */
        data class Paused(
            val progress: Int,
            val totalRead: Long,
            val contentLength: Long,
        ) : DownloadStatus
    }

    @Stable
    data class DownloadItem(
        val id: UUID,
        val fileName: String,
        val status: DownloadStatus,
        val enqueuedAt: Long,
        /** ダウンロード開始時に表示していたページのURL。不明な場合は null */
        val originPageUrl: String?,
    )

    @Stable
    data class Callbacks(
        val onCancel: (UUID) -> Unit,
        /** ダウンロード中のアイテムを一時停止する */
        val onPause: (UUID) -> Unit,
        val onOpenFile: (fileUri: String) -> Unit,
        val onOpenDownloadsFolder: () -> Unit,
        /** 失敗したダウンロードを再開する */
        val onResume: (UUID) -> Unit,
        /** ダウンロード開始時のページを新しいタブで開く */
        val onOpenOriginPage: (url: String) -> Unit,
    )
}
