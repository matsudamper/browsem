package net.matsudamper.browser.screen.crashlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.crashlog.CrashLogListItem
import net.matsudamper.browser.data.crashlog.CrashLogRepository
import net.matsudamper.browser.ui.settings.crash.CrashLogsScreenUiState

internal class CrashLogsScreenViewModel(
    private val crashLogRepository: CrashLogRepository,
) : ViewModel() {

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    private val viewModelStateFlow = MutableStateFlow(ViewModelState())

    private val callbacks = object : CrashLogsScreenUiState.Callbacks {
        override fun onClickEntry(id: Long) {
            eventHandler.trySend { it.navigateToDetail(id) }
        }

        override fun onClickDeleteAll() {
            viewModelStateFlow.update { it.copy(showDeleteAllDialog = true) }
        }

        override fun onConfirmDeleteAll() {
            viewModelScope.launch { crashLogRepository.deleteAll() }
            viewModelStateFlow.update { it.copy(showDeleteAllDialog = false) }
        }

        override fun onDismissDeleteAllDialog() {
            viewModelStateFlow.update { it.copy(showDeleteAllDialog = false) }
        }
    }

    val uiState: StateFlow<CrashLogsScreenUiState> = MutableStateFlow(
        CrashLogsScreenUiState(
            callbacks = callbacks,
            isLoading = true,
            entries = emptyList(),
            showDeleteAllDialog = false,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            viewModelStateFlow.collectLatest { state ->
                uiStateFlow.update {
                    CrashLogsScreenUiState(
                        callbacks = callbacks,
                        isLoading = state.isLoading,
                        entries = state.entries,
                        showDeleteAllDialog = state.showDeleteAllDialog,
                    )
                }
            }
        }
    }.asStateFlow()

    init {
        viewModelScope.launch {
            crashLogRepository.observeAllSummaries().collect { entries ->
                viewModelStateFlow.update { it.copy(entries = entries, isLoading = false) }
            }
        }
    }

    interface Event {
        fun navigateToDetail(crashLogId: Long)
    }

    data class ViewModelState(
        val isLoading: Boolean = true,
        val entries: List<CrashLogListItem> = emptyList(),
        val showDeleteAllDialog: Boolean = false,
    )
}
