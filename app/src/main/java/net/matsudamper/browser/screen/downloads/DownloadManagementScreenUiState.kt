package net.matsudamper.browser.screen.downloads

import androidx.compose.runtime.Stable
import java.util.UUID

@Stable
internal data class DownloadManagementScreenUiState(
    val downloads: List<DownloadItem>,
    val callbacks: Callbacks,
) {
    @Stable
    data class DownloadItem(
        val id: UUID,
        val fileName: String,
        val progress: Int,
        val totalRead: Long,
        val contentLength: Long,
        val isIndeterminate: Boolean,
    )

    @Stable
    data class Callbacks(
        val onCancel: (UUID) -> Unit,
    )
}
