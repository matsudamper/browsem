package net.matsudamper.browser.screen.crashlog

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.crashlog.CrashLogEntity
import net.matsudamper.browser.data.crashlog.CrashLogListItem
import net.matsudamper.browser.data.crashlog.CrashLogRepository
import net.matsudamper.browser.ui.settings.crash.CrashLogsScreenUiState

internal class CrashLogsScreenViewModel(
    private val crashLogRepository: CrashLogRepository,
) : ViewModel() {

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    private val viewModelStateFlow = MutableStateFlow(ViewModelState())

    private val callbacks = object : CrashLogsScreenUiState.Callbacks {
        override fun onClickCopyAll() {
            val selectedTag = viewModelStateFlow.value.selectedTag
            viewModelScope.launch {
                val text = crashLogRepository.getAll()
                    .filter { selectedTag == null || it.tag == selectedTag }
                    .joinToString(separator = "\n\n", transform = ::formatForCopy)
                eventHandler.trySend { it.copyToClipboard(text) }
            }
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
            selectedTagLabel = ALL_TAGS_LABEL,
            tagOptions = listOf(),
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
        val tags = state.entries.map { it.tag }.distinct()
        val selectedTag = state.selectedTag?.takeIf { selected -> tags.contains(selected) }
        val visibleEntries = if (selectedTag == null) {
            state.entries
        } else {
            state.entries.filter { it.tag == selectedTag }
        }
        return CrashLogsScreenUiState(
            callbacks = callbacks,
            isLoading = state.isLoading,
            selectedTagLabel = selectedTag ?: ALL_TAGS_LABEL,
            tagOptions = toTagOptions(tags),
            entries = visibleEntries.map(::toEntryItem),
            showDeleteAllDialog = state.showDeleteAllDialog,
        )
    }

    private fun toTagOptions(tags: List<String>): List<CrashLogsScreenUiState.TagOption> {
        if (tags.isEmpty()) return listOf()
        val allTags = tagOption(
            label = ALL_TAGS_LABEL,
            onClick = {
                viewModelStateFlow.update { it.copy(selectedTag = null) }
            },
        )
        val tagItems = tags.map { tag ->
            tagOption(
                label = tag,
                onClick = {
                    viewModelStateFlow.update { it.copy(selectedTag = tag) }
                },
            )
        }
        return listOf(allTags) + tagItems
    }

    private fun tagOption(
        label: String,
        onClick: () -> Unit,
    ): CrashLogsScreenUiState.TagOption {
        return CrashLogsScreenUiState.TagOption(
            label = label,
            listener = object : CrashLogsScreenUiState.TagOption.Listener {
                override fun onClick() {
                    onClick()
                }
            },
        )
    }

    private fun toEntryItem(entry: CrashLogListItem): CrashLogsScreenUiState.EntryItem {
        return CrashLogsScreenUiState.EntryItem(
            id = entry.id,
            tag = entry.tag,
            title = entry.title,
            occurredAt = entry.occurredAt,
            listener = object : CrashLogsScreenUiState.EntryItem.Listener {
                override fun onClick() {
                    eventHandler.trySend { it.navigateToDetail(entry.id) }
                }
            },
        )
    }

    private fun formatForCopy(entry: CrashLogEntity): String {
        val occurredAt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS", Locale.US).format(Date(entry.occurredAt))
        return "$occurredAt [${entry.tag}] ${entry.title}\n${entry.body}"
    }

    interface Event {
        fun navigateToDetail(crashLogId: Long)
        fun copyToClipboard(text: String)
    }

    data class ViewModelState(
        val isLoading: Boolean = true,
        val entries: List<CrashLogListItem> = listOf(),
        val selectedTag: String? = null,
        val showDeleteAllDialog: Boolean = false,
    )

    private companion object {
        const val ALL_TAGS_LABEL = "すべて"
    }
}
