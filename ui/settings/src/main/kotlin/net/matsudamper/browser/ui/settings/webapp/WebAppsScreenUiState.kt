package net.matsudamper.browser.ui.settings.webapp

import androidx.compose.runtime.Stable

@Stable
data class WebAppsScreenUiState(
    val isLoading: Boolean,
    val entries: List<EntryItem>,
    val deleteConfirmDialog: DeleteConfirmDialog?,
    val renameDialog: RenameDialog?,
) {
    @Stable
    data class EntryItem(
        val title: String,
        val startUrl: String,
        val profileName: String,
        val listener: Listener,
    ) {
        @Stable
        interface Listener {
            fun onClick()
            fun onClickDelete()
        }
    }

    @Stable
    data class RenameDialog(
        val currentTitle: String,
        val listener: Listener,
    ) {
        @Stable
        interface Listener {
            fun onConfirm(title: String)
            fun onDismiss()
        }
    }

    @Stable
    data class DeleteConfirmDialog(
        val title: String,
        val listener: Listener,
    ) {
        @Stable
        interface Listener {
            fun onConfirm()
            fun onDismiss()
        }
    }
}
