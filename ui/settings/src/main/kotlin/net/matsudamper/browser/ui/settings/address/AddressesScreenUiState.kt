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
        val listener: Listener,
    ) {
        @Stable
        interface Listener {
            fun onClick()
            fun onDelete()
        }
    }

    interface Callbacks {
        fun onClickAdd()
        fun onClickDeleteAll()
        fun onConfirmDeleteAll()
        fun onDismissDeleteAllDialog()
    }
}

internal object PreviewAddressesEntryListener : AddressesScreenUiState.EntryItem.Listener {
    override fun onClick() = Unit
    override fun onDelete() = Unit
}
