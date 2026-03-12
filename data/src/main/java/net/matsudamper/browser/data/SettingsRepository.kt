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
        dataStore.updateData { current ->
            current.toBuilder()
                .setHomepageType(settings.homepageType)
                .setCustomHomepageUrl(settings.customHomepageUrl)
                .setSearchProvider(settings.searchProvider)
                .setCustomSearchUrl(settings.customSearchUrl)
                .setThemeMode(settings.themeMode)
                .setTranslationProvider(settings.translationProvider)
                .setEnableThirdPartyCa(settings.enableThirdPartyCa)
                .apply {
                    if (settings.hasEnableWebSuggestions()) {
                        setEnableWebSuggestions(settings.enableWebSuggestions)
                    } else {
                        clearEnableWebSuggestions()
                    }
                }
                .build()
        }
    }

    suspend fun setHomepageType(type: HomepageType) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setHomepageType(type)
                .build()
        }
    }

    suspend fun setCustomHomepageUrl(url: String) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setCustomHomepageUrl(url)
                .build()
        }
    }

    suspend fun setSearchProvider(provider: SearchProvider) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setSearchProvider(provider)
                .build()
        }
    }

    suspend fun setCustomSearchUrl(url: String) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setCustomSearchUrl(url)
                .build()
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setThemeMode(mode)
                .build()
        }
    }

    suspend fun setTranslationProvider(provider: TranslationProvider) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setTranslationProvider(provider)
                .build()
        }
    }

    suspend fun setEnableThirdPartyCa(enabled: Boolean) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setEnableThirdPartyCa(enabled)
                .build()
        }
    }

    suspend fun setEnableWebSuggestions(enabled: Boolean) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setEnableWebSuggestions(enabled)
                .build()
        }
    }

    suspend fun addNotificationAllowedOrigin(origin: String) {
        dataStore.updateData { current ->
            if (current.notificationAllowedOriginsList.contains(origin)) {
                current
            } else {
                current.toBuilder().addNotificationAllowedOrigins(origin).build()
            }
        }
    }

    suspend fun removeNotificationAllowedOrigin(origin: String) {
        dataStore.updateData { current ->
            if (!current.notificationAllowedOriginsList.contains(origin)) {
                current
            } else {
                val newList = current.notificationAllowedOriginsList.filter { it != origin }
                current.toBuilder()
                    .clearNotificationAllowedOrigins()
                    .addAllNotificationAllowedOrigins(newList)
                    .build()
            }
        }
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

fun BrowserSettings.resolvedEnableWebSuggestions(): Boolean {
    return if (hasEnableWebSuggestions()) {
        enableWebSuggestions
    } else {
        false
    }
}
