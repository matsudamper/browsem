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
            typeFilters = listOf(),
            entries = listOf(),
            showDeleteAllDialog = false,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            viewModelStateFlow.collectLatest { state ->
                uiStateFlow.value = toUiState(state)
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

    private fun toUiState(state: ViewModelState): CrashLogsScreenUiState {
        val types = state.entries.map { it.type }.distinct()
        val selectedType = state.selectedType?.takeIf { selected -> types.contains(selected) }
        val visibleEntries = if (selectedType == null) {
            state.entries
        } else {
            state.entries.filter { it.type == selectedType }
        }
        return CrashLogsScreenUiState(
            callbacks = callbacks,
            isLoading = state.isLoading,
            typeFilters = toTypeFilters(types, selectedType),
            entries = visibleEntries.map(::toEntryItem),
            showDeleteAllDialog = state.showDeleteAllDialog,
        )
    }

    private fun toTypeFilters(
        types: List<String>,
        selectedType: String?,
    ): List<CrashLogsScreenUiState.TypeFilterItem> {
        if (types.isEmpty()) return listOf()
        val allTypes = typeFilterItem(
            label = ALL_TYPES_LABEL,
            isSelected = selectedType == null,
            onClick = {
                viewModelStateFlow.update { it.copy(selectedType = null) }
            },
        )
        val typeItems = types.map { type ->
            typeFilterItem(
                label = type,
                isSelected = type == selectedType,
                onClick = {
                    viewModelStateFlow.update { it.copy(selectedType = type) }
                },
            )
        }
        return listOf(allTypes) + typeItems
    }

    private fun typeFilterItem(
        label: String,
        isSelected: Boolean,
        onClick: () -> Unit,
    ): CrashLogsScreenUiState.TypeFilterItem {
        return CrashLogsScreenUiState.TypeFilterItem(
            label = label,
            isSelected = isSelected,
            listener = object : CrashLogsScreenUiState.TypeFilterItem.Listener {
                override fun onClick() {
                    onClick()
                }
            },
        )
    }

    private fun toEntryItem(entry: CrashLogListItem): CrashLogsScreenUiState.EntryItem {
        return CrashLogsScreenUiState.EntryItem(
            id = entry.id,
            type = entry.type,
            title = entry.title,
            occurredAt = entry.occurredAt,
            listener = object : CrashLogsScreenUiState.EntryItem.Listener {
                override fun onClick() {
                    eventHandler.trySend { it.navigateToDetail(entry.id) }
                }
            },
        )
    }

    interface Event {
        fun navigateToDetail(crashLogId: Long)
    }

    data class ViewModelState(
        val isLoading: Boolean = true,
        val entries: List<CrashLogListItem> = listOf(),
        val selectedType: String? = null,
        val showDeleteAllDialog: Boolean = false,
    )

    private companion object {
        const val ALL_TYPES_LABEL = "すべて"
    }
}
