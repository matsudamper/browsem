package net.matsudamper.browser.screen.downloads

import androidx.compose.runtime.Stable
import java.util.UUID

@Stable
internal data class DownloadManagementScreenUiState(
    val downloads: List<DownloadItem>,
    val callbacks: Callbacks,
) {
    @Stable
    sealed interface DownloadStatus {
        /** ダウンロード進行中 */
        data class InProgress(
            val progress: Int,
            val totalRead: Long,
            val contentLength: Long,
            val isIndeterminate: Boolean,
        ) : DownloadStatus

        /** ダウンロード完了 */
        data class Completed(val fileUri: String) : DownloadStatus

        /**
         * ダウンロード失敗。
         * [canResume] が true の場合は再開ボタンを表示する。
         */
        data class Failed(val canResume: Boolean) : DownloadStatus

        /** ダウンロードキャンセル */
        data object Cancelled : DownloadStatus
    }

    @Stable
    data class DownloadItem(
        val id: UUID,
        val fileName: String,
        val status: DownloadStatus,
        /** ダウンロードを開始した時刻（エポックミリ秒） */
        val enqueuedAt: Long,
    )

    @Stable
    data class Callbacks(
        val onCancel: (UUID) -> Unit,
        val onOpenFile: (fileUri: String) -> Unit,
        val onOpenDownloadsFolder: () -> Unit,
        /** 失敗したダウンロードを再開する */
        val onResume: (UUID) -> Unit,
    )
}
