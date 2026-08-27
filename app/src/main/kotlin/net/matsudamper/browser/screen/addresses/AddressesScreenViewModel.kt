package net.matsudamper.browser.screen.addresses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.address.AddressEntity
import net.matsudamper.browser.data.address.AddressRepository
import net.matsudamper.browser.ui.settings.AddressesScreenUiState

internal class AddressesScreenViewModel(
    private val addressRepository: AddressRepository,
) : ViewModel() {

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    private val viewModelStateFlow = MutableStateFlow(ViewModelState())

    private val callbacks = object : AddressesScreenUiState.Callbacks {
        override fun onClickAdd() {
            eventHandler.trySend { it.navigateToEdit(NEW_ADDRESS_ID) }
        }

        override fun onClickEntry(id: Long) {
            eventHandler.trySend { it.navigateToEdit(id) }
        }

        override fun onDeleteEntry(id: Long) {
            viewModelScope.launch { addressRepository.deleteById(id) }
        }

        override fun onClickDeleteAll() {
            viewModelStateFlow.update { it.copy(showDeleteAllDialog = true) }
        }

        override fun onConfirmDeleteAll() {
            viewModelScope.launch { addressRepository.deleteAll() }
            viewModelStateFlow.update { it.copy(showDeleteAllDialog = false) }
        }

        override fun onDismissDeleteAllDialog() {
            viewModelStateFlow.update { it.copy(showDeleteAllDialog = false) }
        }
    }

    val uiState: StateFlow<AddressesScreenUiState> = MutableStateFlow(
        AddressesScreenUiState(
            callbacks = callbacks,
            entries = emptyList(),
            showDeleteAllDialog = false,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            viewModelStateFlow.collectLatest { state ->
                uiStateFlow.update {
                    AddressesScreenUiState(
                        callbacks = callbacks,
                        entries = state.entries,
                        showDeleteAllDialog = state.showDeleteAllDialog,
                    )
                }
            }
        }
    }.asStateFlow()

    init {
        viewModelScope.launch {
            addressRepository.observeAll().collect { entries ->
                viewModelStateFlow.update { it.copy(entries = entries) }
            }
        }
    }

    interface Event {
        fun navigateToEdit(addressId: Long)
    }

    data class ViewModelState(
        val entries: List<AddressEntity> = emptyList(),
        val showDeleteAllDialog: Boolean = false,
    )

    companion object {
        const val NEW_ADDRESS_ID: Long = 0L
    }
}
