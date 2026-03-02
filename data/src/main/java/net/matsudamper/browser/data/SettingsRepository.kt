package net.matsudamper.browser.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.google.protobuf.ByteString
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

    suspend fun updateTabStates(
        tabs: List<PersistedTabState>,
        selectedTabIndex: Int,
    ) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            val currentTabs = current.tabStatesList.map {
                PersistedTabState(
                    url = it.url,
                    sessionState = it.sessionState,
                    title = it.title,
                    previewImageWebp = it.previewImageWebp,
                )
            }
            if (currentTabs == tabs && current.selectedTabIndex == selectedTabIndex) {
                return@updateData current
            }
            builder.clearTabStates()
            tabs.forEach { tab ->
                builder.addTabStates(
                    BrowserTabState.newBuilder()
                        .setUrl(tab.url)
                        .setSessionState(tab.sessionState)
                        .setTitle(tab.title)
                        .setPreviewImageWebp(tab.previewImageWebp)
                        .build()
                )
            }
            builder.selectedTabIndex = selectedTabIndex
            builder.build()
        }
    }
}

data class PersistedTabState(
    val url: String,
    val sessionState: String,
    val title: String,
    val previewImageWebp: ByteString = ByteString.EMPTY,
)

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
