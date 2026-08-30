package net.matsudamper.browser.ui.extensions

import androidx.compose.runtime.Stable

@Stable
data class ExtensionsScreenUiState(
    val callbacks: Callbacks,
    val loadingState: LoadingState,
    val errorMessage: String?,
    val uninstallingId: String?,
    val togglingId: String?,
    val isTogglingGlobal: Boolean,
    val isInstalling: Boolean,
) {
    interface Callbacks {
        fun refreshExtensions()

        /** ZIP / XPI ファイルを選択して拡張機能をインストールする */
        fun installExtensionFromFile()

        /** 拡張機能全体の有効/無効を切り替える */
        fun setExtensionsGloballyEnabled(enabled: Boolean)
        fun dismissError()
    }

    sealed interface LoadingState {
        data object Loading : LoadingState

        data class Loaded(
            val extensions: List<ExtensionUiState>,
            val areExtensionsEnabled: Boolean,
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
        val onOpenSettings: () -> Unit,
        val onUninstall: () -> Unit,
        val onToggle: (Boolean) -> Unit,
    )
}
