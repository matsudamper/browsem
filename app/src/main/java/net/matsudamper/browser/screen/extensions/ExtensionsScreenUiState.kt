package net.matsudamper.browser.screen.extensions

internal data class ExtensionsScreenUiState(
    val callbacks: Callbacks,
    val loadingState: LoadingState,
    val errorMessage: String?,
    val uninstallingId: String?,
) {
    interface Callbacks {
        fun refreshExtensions()
        fun uninstallExtension(extensionId: String)
        fun openExtensionSettings(extensionId: String)
        fun dismissError()
    }

    sealed interface LoadingState {
        object Loading : LoadingState
        data class Loaded(
            val extensions: List<ExtensionUiState>,
        ) : LoadingState
    }

    data class ExtensionUiState(
        val id: String,
        val displayName: String,
        val version: String,
        val hasSettingsPage: Boolean,
    )
}
