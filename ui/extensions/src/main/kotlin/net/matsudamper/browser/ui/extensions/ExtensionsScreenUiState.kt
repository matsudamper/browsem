package net.matsudamper.browser.ui.extensions

import androidx.compose.runtime.Stable

@Stable
data class ExtensionsScreenUiState(
    val callbacks: Callbacks,
    val loadingState: LoadingState,
    val errorMessage: String?,
    val uninstallingId: String?,
    val togglingId: String?,
) {
    interface Callbacks {
        fun refreshExtensions()
        fun uninstallExtension(extensionId: String)
        fun openExtensionSettings(extensionId: String)
        fun setExtensionEnabled(extensionId: String, enabled: Boolean)
        fun dismissError()
    }

    sealed interface LoadingState {
        data object Loading : LoadingState

        data class Loaded(
            val extensions: List<ExtensionUiState>,
        ) : LoadingState
    }

    @Stable
    data class ExtensionUiState(
        val id: String,
        val displayName: String,
        val version: String,
        val hasSettingsPage: Boolean,
        val isEnabled: Boolean,
        val isBuiltIn: Boolean,
    )
}
