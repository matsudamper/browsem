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
import net.matsudamper.browser.data.forminput.FormInputRepository
import net.matsudamper.browser.data.forminput.displayFormInputPath
import net.matsudamper.browser.ui.settings.SiteFormInputPathScreenUiState

@Stable
internal class SiteFormInputPathScreenViewModel(
    private val host: String,
    private val path: String,
    private val formInputRepository: FormInputRepository,
) : ViewModel() {
    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    interface Event {
        fun navigateBack()
        fun navigateBackAfterDeleted()
    }

    private data class ViewModelState(
        val pathEnabled: Boolean = true,
        val fields: List<SiteFormInputPathScreenUiState.FieldEntry> = emptyList(),
        val deleteFieldConfirm: String? = null,
        val deletePathConfirm: Boolean = false,
    )

    private val viewModelStateFlow = MutableStateFlow(ViewModelState())

    private val callbacks = object : SiteFormInputPathScreenUiState.Callbacks {
        override fun navigateBack() {
            eventHandler.trySend { it.navigateBack() }
        }

        override fun setPathEnabled(enabled: Boolean) {
            viewModelScope.launch {
                formInputRepository.setPathEnabled(host, path, enabled)
            }
        }

        override fun setFieldEnabled(fieldKey: String, enabled: Boolean) {
            viewModelScope.launch {
                formInputRepository.setFieldEnabled(host, path, fieldKey, enabled)
            }
        }

        override fun requestDeleteField(fieldKey: String) {
            viewModelStateFlow.update { it.copy(deleteFieldConfirm = fieldKey) }
        }

        override fun confirmDeleteField() {
            val fieldKey = viewModelStateFlow.value.deleteFieldConfirm ?: return
            viewModelStateFlow.update { it.copy(deleteFieldConfirm = null) }
            viewModelScope.launch {
                formInputRepository.deleteField(host, path, fieldKey)
            }
        }

        override fun dismissDeleteFieldConfirm() {
            viewModelStateFlow.update { it.copy(deleteFieldConfirm = null) }
        }

        override fun requestDeletePath() {
            viewModelStateFlow.update { it.copy(deletePathConfirm = true) }
        }

        override fun confirmDeletePath() {
            viewModelStateFlow.update { it.copy(deletePathConfirm = false) }
            viewModelScope.launch {
                formInputRepository.deletePath(host, path)
                eventHandler.trySend { it.navigateBackAfterDeleted() }
            }
        }

        override fun dismissDeletePathConfirm() {
            viewModelStateFlow.update { it.copy(deletePathConfirm = false) }
        }
    }

    val uiState: StateFlow<SiteFormInputPathScreenUiState> = MutableStateFlow(
        SiteFormInputPathScreenUiState(
            callbacks = callbacks,
            host = host,
            path = path,
            displayPath = displayFormInputPath(path),
            pathEnabled = true,
            fields = emptyList(),
            deleteFieldConfirm = null,
            deletePathConfirm = false,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            combine(
                formInputRepository.observeSavedFields(host, path),
                formInputRepository.observePathEnabled(host, path),
                viewModelStateFlow,
            ) { fields, pathEnabled, dialogState ->
                SiteFormInputPathScreenUiState(
                    callbacks = callbacks,
                    host = host,
                    path = path,
                    displayPath = displayFormInputPath(path),
                    pathEnabled = pathEnabled,
                    fields = fields.map { field ->
                        SiteFormInputPathScreenUiState.FieldEntry(
                            fieldKey = field.fieldKey,
                            previewText = field.values.joinToString(" / "),
                            enabled = field.enabled,
                        )
                    },
                    deleteFieldConfirm = dialogState.deleteFieldConfirm,
                    deletePathConfirm = dialogState.deletePathConfirm,
                )
            }.collectLatest { state ->
                uiStateFlow.value = state
            }
        }
    }.asStateFlow()
}
