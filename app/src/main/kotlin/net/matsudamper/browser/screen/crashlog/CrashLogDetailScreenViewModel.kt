package net.matsudamper.browser.screen.crashlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.crashlog.CrashLogEntity
import net.matsudamper.browser.data.crashlog.CrashLogRepository
import net.matsudamper.browser.ui.settings.CrashLogDetailScreenUiState

internal class CrashLogDetailScreenViewModel(
    private val crashLogRepository: CrashLogRepository,
    private val crashLogId: Long,
) : ViewModel() {

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    private val callbacks = object : CrashLogDetailScreenUiState.Callbacks {
        override fun onClickCopyBody() {
            val body = uiState.value.entry?.body ?: return
            eventHandler.trySend { it.copyToClipboard(body) }
        }
    }

    val uiState: StateFlow<CrashLogDetailScreenUiState> = MutableStateFlow(
        CrashLogDetailScreenUiState(
            callbacks = callbacks,
            isLoading = true,
            entry = null,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            val entry = crashLogRepository.getById(crashLogId)
            uiStateFlow.update {
                CrashLogDetailScreenUiState(
                    callbacks = callbacks,
                    isLoading = false,
                    entry = entry,
                )
            }
        }
    }.asStateFlow()

    interface Event {
        fun copyToClipboard(text: String)
    }
}
