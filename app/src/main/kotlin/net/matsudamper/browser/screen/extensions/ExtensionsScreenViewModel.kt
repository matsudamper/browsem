package net.matsudamper.browser.screen.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.ui.extensions.ExtensionsScreenUiState
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

internal class ExtensionsScreenViewModel(
    private val runtime: GeckoRuntime,
) : ViewModel() {

    private val viewModelStateFlow = MutableStateFlow(ViewModelState())
    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    private val callbacks = object : ExtensionsScreenUiState.Callbacks {
        override fun refreshExtensions() {
            loadExtensions()
        }

        override fun uninstallExtension(extensionId: String) {
            val extension = viewModelStateFlow.value.extensions
                .firstOrNull { it.id == extensionId } ?: return
            viewModelStateFlow.update { it.copy(uninstallingId = extensionId) }
            runtime.webExtensionController.uninstall(extension).accept(
                {
                    viewModelStateFlow.update { it.copy(uninstallingId = null) }
                    loadExtensions()
                },
                { error ->
                    viewModelStateFlow.update {
                        it.copy(
                            uninstallingId = null,
                            errorMessage = error?.message ?: "拡張機能のアンインストールに失敗しました。",
                        )
                    }
                },
            )
        }

        override fun openExtensionSettings(extensionId: String) {
            val extension = viewModelStateFlow.value.extensions
                .firstOrNull { it.id == extensionId } ?: return
            val optionsPageUrl = extension.metaData.optionsPageUrl?.takeIf { it.isNotBlank() }
            if (optionsPageUrl != null) {
                eventHandler.trySend { it.navigateToExtensionSettings(optionsPageUrl) }
            } else {
                viewModelStateFlow.update { it.copy(errorMessage = "この拡張機能には設定画面がありません。") }
            }
        }

        override fun dismissError() {
            viewModelStateFlow.update { it.copy(errorMessage = null) }
        }
    }

    val uiState: StateFlow<ExtensionsScreenUiState> = MutableStateFlow(
        ExtensionsScreenUiState(
            callbacks = callbacks,
            loadingState = ExtensionsScreenUiState.LoadingState.Loading,
            errorMessage = null,
            uninstallingId = null,
        )
    ).also { uiStateFlow ->
        viewModelScope.launch {
            viewModelStateFlow.collectLatest { state ->
                uiStateFlow.update {
                    ExtensionsScreenUiState(
                        callbacks = callbacks,
                        loadingState = if (state.isLoading) {
                            ExtensionsScreenUiState.LoadingState.Loading
                        } else {
                            ExtensionsScreenUiState.LoadingState.Loaded(
                                extensions = state.extensions.map { ext ->
                                    ExtensionsScreenUiState.ExtensionUiState(
                                        id = ext.id,
                                        displayName = ext.metaData.name?.takeIf { it.isNotBlank() } ?: ext.id,
                                        version = ext.metaData.version,
                                        hasSettingsPage = ext.metaData.optionsPageUrl?.isNotBlank() == true,
                                    )
                                },
                            )
                        },
                        errorMessage = state.errorMessage,
                        uninstallingId = state.uninstallingId,
                    )
                }
            }
        }
    }.asStateFlow()

    init {
        loadExtensions()
    }

    private fun loadExtensions() {
        viewModelStateFlow.update { it.copy(isLoading = true) }
        runtime.webExtensionController.list().accept(
            { list ->
                viewModelStateFlow.update {
                    it.copy(
                        isLoading = false,
                        extensions = (list ?: emptyList()).sortedBy { ext ->
                            (ext.metaData.name?.takeIf { n -> n.isNotBlank() } ?: ext.id).lowercase()
                        },
                    )
                }
            },
            { error ->
                viewModelStateFlow.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error?.message ?: "拡張機能一覧の取得に失敗しました。",
                    )
                }
            },
        )
    }

    interface Event {
        fun navigateToExtensionSettings(url: String)
    }

    data class ViewModelState(
        val extensions: List<WebExtension> = emptyList(),
        val isLoading: Boolean = true,
        val uninstallingId: String? = null,
        val errorMessage: String? = null,
    )
}
