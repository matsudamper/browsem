package net.matsudamper.browser

import androidx.lifecycle.ViewModel
import org.mozilla.geckoview.GeckoRuntime

internal class ExtensionsScreenViewModel(
    val runtime: GeckoRuntime,
    private val onOpenExtensionSettingsRequest: (String) -> Unit,
) : ViewModel() {
    fun onOpenExtensionSettings(optionsPageUrl: String) {
        onOpenExtensionSettingsRequest(optionsPageUrl)
    }
}
