package net.matsudamper.browser.screen.siteforminput

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.forminput.FormInputOrigin
import net.matsudamper.browser.data.forminput.FormInputRepository
import net.matsudamper.browser.data.forminput.displayFormInputOrigin
import net.matsudamper.browser.data.forminput.displayFormInputPath
import net.matsudamper.browser.ui.settings.SiteFormInputPathScreenUiState

@Stable
internal class SiteFormInputPathScreenViewModel(
    private val origin: FormInputOrigin,
    private val path: String,
    private val formInputRepository: FormInputRepository,
) : ViewModel() {
    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    interface Event {
        fun navigateBack()
        fun navigateBackAfterDeleted()
        fun navigateToField(fieldKey: String)
    }

    private data class ViewModelState(
        val deletePathConfirm: Boolean = false,
    )

    private val viewModelStateFlow = MutableStateFlow(ViewModelState())

    private val callbacks = object : SiteFormInputPathScreenUiState.Callbacks {
        override fun navigateBack() {
            eventHandler.trySend { it.navigateBack() }
        }

        override fun setPathEnabled(enabled: Boolean) {
            viewModelScope.launch {
                formInputRepository.setPathEnabled(origin, path, enabled)
            }
        }

        override fun openField(fieldKey: String) {
            eventHandler.trySend { it.navigateToField(fieldKey) }
        }

        override fun requestDeletePath() {
            viewModelStateFlow.update { it.copy(deletePathConfirm = true) }
        }

        override fun confirmDeletePath() {
            viewModelStateFlow.update { it.copy(deletePathConfirm = false) }
            viewModelScope.launch {
                formInputRepository.deletePath(origin, path)
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
            displayOrigin = displayFormInputOrigin(origin),
            path = path,
            displayPath = displayFormInputPath(path),
            pathEnabled = true,
            fields = emptyList(),
            deletePathConfirm = false,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            combine(
                formInputRepository.observeSavedFields(origin, path),
                formInputRepository.observePathEnabled(origin, path),
                viewModelStateFlow,
            ) { fields, pathEnabled, dialogState ->
                SiteFormInputPathScreenUiState(
                    callbacks = callbacks,
                    displayOrigin = displayFormInputOrigin(origin),
                    path = path,
                    displayPath = displayFormInputPath(path),
                    pathEnabled = pathEnabled,
                    fields = fields.map { field ->
                        SiteFormInputPathScreenUiState.FieldEntry(
                            fieldKey = field.fieldKey,
                            previewText = field.previewValues.joinToString("/"),
                        )
                    },
                    deletePathConfirm = dialogState.deletePathConfirm,
                )
            }.collectLatest { state ->
                uiStateFlow.value = state
            }
        }
    }.asStateFlow()
}
