package net.matsudamper.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

@Composable
internal fun rememberExtensionsScreenState(
    runtime: GeckoRuntime,
): ExtensionsScreenState {
    return remember(runtime) {
        ExtensionsScreenState(runtime).also { it.refreshExtensions() }
    }
}

@Stable
internal class ExtensionsScreenState(
    private val runtime: GeckoRuntime,
) {
    var extensions by mutableStateOf<List<WebExtension>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var uninstallingId by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)

    fun refreshExtensions() {
        isLoading = true
        runtime.webExtensionController.list().accept(
            { list ->
                extensions = (list ?: emptyList()).sortedBy { extension ->
                    (extension.metaData.name?.takeIf { it.isNotBlank() } ?: extension.id)
                        .lowercase()
                }
                isLoading = false
            },
            { error ->
                errorMessage = error?.message ?: "拡張機能一覧の取得に失敗しました。"
                isLoading = false
            },
        )
    }

    fun uninstallExtension(extension: WebExtension) {
        uninstallingId = extension.id
        runtime.webExtensionController.uninstall(extension).accept(
            {
                uninstallingId = null
                refreshExtensions()
            },
            { error ->
                uninstallingId = null
                errorMessage = error?.message ?: "拡張機能のアンインストールに失敗しました。"
            },
        )
    }

    fun openExtensionSettings(
        extension: WebExtension,
        onOpenExtensionSettings: (String) -> Unit,
    ) {
        val optionsPageUrl = extension.metaData.optionsPageUrl
            ?.takeIf { it.isNotBlank() }
        if (optionsPageUrl != null) {
            onOpenExtensionSettings(optionsPageUrl)
        } else {
            errorMessage = "この拡張機能には設定画面がありません。"
        }
    }

    fun dismissError() {
        errorMessage = null
    }
}
