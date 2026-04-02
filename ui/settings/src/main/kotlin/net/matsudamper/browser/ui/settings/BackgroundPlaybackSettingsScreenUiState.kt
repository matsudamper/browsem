package net.matsudamper.browser.ui.settings

data class BackgroundPlaybackSettingsScreenUiState(
    val callbacks: Callbacks,
    val allowedDomains: List<String>,
) {
    interface Callbacks {
        fun addDomain(domain: String)
        fun removeDomain(domain: String)
        fun resetToDefaults()
    }
}
