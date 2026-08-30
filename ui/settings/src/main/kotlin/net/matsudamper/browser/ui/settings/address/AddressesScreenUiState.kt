package net.matsudamper.browser.ui.settings.address

import androidx.compose.runtime.Stable

@Stable
data class AddressesScreenUiState(
    val callbacks: Callbacks,
    val entries: List<EntryItem>,
    val showDeleteAllDialog: Boolean,
) {
    @Stable
    data class EntryItem(
        val id: Long,
        val displayName: String,
        val displayDetail: String,
        val onClick: () -> Unit,
        val onDelete: () -> Unit,
    )

    interface Callbacks {
        fun onClickAdd()
        fun onClickDeleteAll()
        fun onConfirmDeleteAll()
        fun onDismissDeleteAllDialog()
    }
}
