package net.matsudamper.browser.ui.settings

import net.matsudamper.browser.data.HomepageType
import net.matsudamper.browser.data.SearchProvider
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.data.TranslationProvider

data class SettingsScreenUiState(
    val callbacks: Callbacks,
    val homepageType: HomepageType,
    val customHomepageUrl: String,
    val searchProvider: SearchProvider,
    val customSearchUrl: String,
    val themeMode: ThemeMode,
    val translationProvider: TranslationProvider,
    val enableThirdPartyCa: Boolean,
    val enableWebSuggestions: Boolean,
    val mockLocationEnabled: Boolean,
    val mockLocationInput: String,
    val mockLocationInputError: String?,
) {
    interface Callbacks {
        fun setHomepageType(type: HomepageType)
        fun setCustomHomepageUrl(url: String)
        fun setSearchProvider(provider: SearchProvider)
        fun setCustomSearchUrl(url: String)
        fun setThemeMode(mode: ThemeMode)
        fun setTranslationProvider(provider: TranslationProvider)
        fun setEnableThirdPartyCa(enabled: Boolean)
        fun setEnableWebSuggestions(enabled: Boolean)
        fun setMockLocationEnabled(enabled: Boolean)
        fun setMockLocationInput(input: String)
        fun openMockLocationOnMap()
    }
}
