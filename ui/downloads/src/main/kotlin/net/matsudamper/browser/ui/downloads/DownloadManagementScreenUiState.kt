package net.matsudamper.browser.ui.downloads

import androidx.compose.runtime.Stable
import java.util.UUID

@Stable
data class DownloadManagementScreenUiState(
    val loadingState: LoadingState,
    val callbacks: Callbacks,
) {
    @Stable
    sealed interface LoadingState {
        data object Loading : LoadingState

        @Stable
        data class Loaded(
            val downloads: List<DownloadItem>,
        ) : LoadingState
    }

    @Stable
    sealed interface DownloadStatus {
        data class InProgress(
            val progress: Int,
            val totalRead: Long,
            val contentLength: Long,
            val isIndeterminate: Boolean,
        ) : DownloadStatus

        data class Completed(val fileUri: String) : DownloadStatus

        /**
         * ダウンロード失敗。
         * [canResume] が true の場合は再開ボタンを表示する。
         */
        data class Failed(val canResume: Boolean) : DownloadStatus

        data object Cancelled : DownloadStatus
    }

    @Stable
    data class DownloadItem(
        val id: UUID,
        val fileName: String,
        val status: DownloadStatus,
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
