package net.matsudamper.browser.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.Flow

private val Context.browserSettingsDataStore: DataStore<BrowserSettings> by dataStore(
    fileName = "browser_settings.pb",
    serializer = BrowserSettingsSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { BrowserSettings.getDefaultInstance() },
)

class SettingsRepository(context: Context) {
    private val dataStore = context.browserSettingsDataStore

    val settings: Flow<BrowserSettings> = dataStore.data

    suspend fun updateSettings(settings: BrowserSettings) {
        dataStore.updateData { settings }
    }
}

fun BrowserSettings.resolvedHomepageUrl(): String = when (homepageType) {
    HomepageType.HOMEPAGE_DUCKDUCKGO -> "https://duckduckgo.com"
    HomepageType.HOMEPAGE_CUSTOM -> customHomepageUrl.ifBlank { "https://www.google.com" }
    else -> "https://www.google.com"
}

fun BrowserSettings.resolvedSearchTemplate(): String = when (searchProvider) {
    SearchProvider.DUCKDUCKGO -> "https://duckduckgo.com/?q=%s"
    SearchProvider.CUSTOM -> customSearchUrl.ifBlank { "https://www.google.com/search?q=%s" }
    else -> "https://www.google.com/search?q=%s"
}
