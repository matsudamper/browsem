package net.matsudamper.browser

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.BrowserSettings
import net.matsudamper.browser.data.PersistedTabState
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.resolvedHomepageUrl
import net.matsudamper.browser.data.resolvedSearchTemplate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

@Stable
internal class BrowserViewModel(
    val runtime: GeckoRuntime,
    context: Context,
) : ViewModel() {
    val browserSessionController = BrowserSessionController(runtime)
    val settingsRepository = SettingsRepository(context)

    val settings: StateFlow<BrowserSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var tabPersistenceSignal by mutableLongStateOf(0L)
        private set

    fun bumpTabPersistence() {
        tabPersistenceSignal++
    }

    val homepageUrl: String
        get() = settings.value?.resolvedHomepageUrl() ?: "https://www.google.com"

    val searchTemplate: String
        get() = settings.value?.resolvedSearchTemplate() ?: "https://www.google.com/search?q=%s"

    fun restoreTabs(currentSettings: BrowserSettings) {
        val homepageUrl = currentSettings.resolvedHomepageUrl()
        val persistedTabs = currentSettings.tabStatesList.map { tabState ->
            PersistedBrowserTab(
                url = tabState.url,
                sessionState = tabState.sessionState,
                title = tabState.title,
                previewImageWebp = tabState.previewImageWebp.toByteArray(),
            )
        }
        browserSessionController.restoreTabs(
            homepageUrl = homepageUrl,
            persistedTabs = persistedTabs,
            persistedSelectedTabIndex = currentSettings.selectedTabIndex,
        )
    }

    fun persistTabStates() {
        viewModelScope.launch {
            settingsRepository.updateTabStates(
                tabs = browserSessionController.exportPersistedTabs().map { tab ->
                    PersistedTabState(
                        url = tab.url,
                        sessionState = tab.sessionState,
                        title = tab.title,
                        previewImageWebp = ByteString.copyFrom(tab.previewImageWebp),
                    )
                },
                selectedTabIndex = browserSessionController.selectedTabIndex,
            )
        }
    }

    fun updateSettings(newSettings: BrowserSettings) {
        viewModelScope.launch {
            settingsRepository.updateSettings(newSettings)
        }
    }

    fun addNotificationAllowedOrigin(origin: String) {
        viewModelScope.launch {
            settingsRepository.addNotificationAllowedOrigin(origin)
        }
    }

    fun removeNotificationAllowedOrigin(origin: String) {
        viewModelScope.launch {
            settingsRepository.removeNotificationAllowedOrigin(origin)
        }
    }

    fun handleNotificationPermission(
        uri: String,
        currentSettings: BrowserSettings,
        onDesktopNotificationPermissionRequest: () -> GeckoResult<Int>,
    ): GeckoResult<Int> {
        val allowedOrigins = currentSettings.notificationAllowedOriginsList
        if (allowedOrigins.contains(uri)) {
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
        }
        val androidResult = onDesktopNotificationPermissionRequest()
        return androidResult.then { value ->
            if (value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) {
                addNotificationAllowedOrigin(uri)
            }
            GeckoResult.fromValue(value)
        }
    }

    fun applyRuntimeSettings(currentSettings: BrowserSettings) {
        runtime.settings.setEnterpriseRootsEnabled(currentSettings.enableThirdPartyCa)
    }

    override fun onCleared() {
        super.onCleared()
        browserSessionController.close()
    }
}
