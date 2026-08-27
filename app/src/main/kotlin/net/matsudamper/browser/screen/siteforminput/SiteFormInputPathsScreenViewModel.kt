package net.matsudamper.browser.screen.siteforminput

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.forminput.FormInputRepository
import net.matsudamper.browser.data.forminput.displayFormInputPath
import net.matsudamper.browser.ui.settings.SiteFormInputPathsScreenUiState

internal class SiteFormInputPathsScreenViewModel(
    private val host: String,
    private val formInputRepository: FormInputRepository,
) : ViewModel() {
    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    interface Event {
        fun navigateToPath(path: String)
    }

    private val callbacks = object : SiteFormInputPathsScreenUiState.Callbacks {
        override fun setPathEnabled(path: String, enabled: Boolean) {
            viewModelScope.launch {
                formInputRepository.setPathEnabled(host, path, enabled)
            }
        }

        override fun openPath(path: String) {
            eventHandler.trySend { it.navigateToPath(path) }
        }
    }

    private val uiStateFlow = MutableStateFlow(
        SiteFormInputPathsScreenUiState(
            callbacks = callbacks,
            host = host,
            paths = emptyList(),
        ),
    )

    val uiState: StateFlow<SiteFormInputPathsScreenUiState> = uiStateFlow.also {
        viewModelScope.launch {
            formInputRepository.observeSavedPaths(host).collectLatest { paths ->
                uiStateFlow.update { state ->
                    state.copy(
                        paths = paths.map { path ->
                            SiteFormInputPathsScreenUiState.PathEntry(
                                path = path.path,
                                displayPath = displayFormInputPath(path.path),
                                fieldCount = path.fieldCount,
                                enabled = path.enabled,
                            )
                        },
                    )
                }
            }
        }
    }.asStateFlow()
}
