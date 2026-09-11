package net.matsudamper.browser.ui.settings.site

import androidx.compose.runtime.Stable

@Stable
data class SiteSettingsListScreenUiState(
    val callbacks: Callbacks,
    val query: String,
    val hosts: List<String>,
    val isLoading: Boolean,
    val hasNextPage: Boolean,
) {
    interface Callbacks {
        fun setQuery(query: String)

        fun loadNextPage()

        fun openSiteSettings(host: String)
    }
}
