package net.matsudamper.browser.ui.downloads

import androidx.compose.runtime.Stable
import java.util.UUID

@Stable
data class DownloadManagementScreenUiState(
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

        data class Completed(val fileUri: String) : DownloadStatus

        data object Failed : DownloadStatus

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
    )
}
