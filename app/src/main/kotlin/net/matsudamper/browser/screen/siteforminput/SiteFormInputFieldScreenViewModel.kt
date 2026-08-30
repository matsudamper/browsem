package net.matsudamper.browser.screen.siteforminput

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.forminput.FormInputOrigin
import net.matsudamper.browser.data.forminput.FormInputRepository
import net.matsudamper.browser.data.forminput.displayFormInputPath
import net.matsudamper.browser.ui.settings.form.SiteFormInputFieldScreenUiState

@Stable
internal class SiteFormInputFieldScreenViewModel(
    private val origin: FormInputOrigin,
    private val path: String,
    private val fieldKey: String,
    private val formInputRepository: FormInputRepository,
) : ViewModel() {
    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    private val viewModelStateFlow = MutableStateFlow(ViewModelState())

    private val callbacks = object : SiteFormInputFieldScreenUiState.Callbacks {
        override fun navigateBack() {
            eventHandler.trySend { it.navigateBack() }
        }

        override fun requestDeleteValue(value: String) {
            viewModelStateFlow.update { it.copy(deleteValueConfirm = value) }
        }

        override fun confirmDeleteValue() {
            val value = viewModelStateFlow.value.deleteValueConfirm ?: return
            viewModelStateFlow.update { it.copy(deleteValueConfirm = null) }
            viewModelScope.launch {
                formInputRepository.deleteValue(origin, path, fieldKey, value)
            }
        }

        override fun dismissDeleteValueConfirm() {
            viewModelStateFlow.update { it.copy(deleteValueConfirm = null) }
        }

        override fun requestDeleteField() {
            viewModelStateFlow.update { it.copy(deleteFieldConfirm = true) }
        }

        override fun confirmDeleteField() {
            viewModelStateFlow.update { it.copy(deleteFieldConfirm = false) }
            viewModelScope.launch {
                formInputRepository.deleteField(origin, path, fieldKey)
                eventHandler.trySend { it.navigateBackAfterDeleted() }
            }
        }

        override fun dismissDeleteFieldConfirm() {
            viewModelStateFlow.update { it.copy(deleteFieldConfirm = false) }
        }
    }

    val uiState: StateFlow<SiteFormInputFieldScreenUiState> = MutableStateFlow(
        SiteFormInputFieldScreenUiState(
            callbacks = callbacks,
            displayPath = displayFormInputPath(path),
            fieldKey = fieldKey,
            values = emptyList(),
            deleteValueConfirm = null,
            deleteFieldConfirm = false,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            combine(
                formInputRepository.observeSavedValues(origin, path, fieldKey),
                viewModelStateFlow,
            ) { values, dialogState ->
                SiteFormInputFieldScreenUiState(
                    callbacks = callbacks,
                    displayPath = displayFormInputPath(path),
                    fieldKey = fieldKey,
                    values = values,
                    deleteValueConfirm = dialogState.deleteValueConfirm,
                    deleteFieldConfirm = dialogState.deleteFieldConfirm,
                )
            }.collectLatest { state ->
                uiStateFlow.value = state
            }
        }
    }.asStateFlow()

    interface Event {
        fun navigateBack()
        fun navigateBackAfterDeleted()
    }

    private data class ViewModelState(
        val deleteValueConfirm: String? = null,
        val deleteFieldConfirm: Boolean = false,
    )
}
