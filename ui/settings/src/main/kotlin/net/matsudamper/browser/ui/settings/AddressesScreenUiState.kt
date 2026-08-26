package net.matsudamper.browser.ui.settings

import net.matsudamper.browser.data.address.AddressEntity

data class AddressesScreenUiState(
    val callbacks: Callbacks,
    val entries: List<AddressEntity>,
    val showDeleteAllDialog: Boolean,
) {
    interface Callbacks {
        fun onClickAdd()
        fun onClickEntry(id: Long)
        fun onDeleteEntry(id: Long)
        fun onClickDeleteAll()
        fun onConfirmDeleteAll()
        fun onDismissDeleteAllDialog()
    }
}
