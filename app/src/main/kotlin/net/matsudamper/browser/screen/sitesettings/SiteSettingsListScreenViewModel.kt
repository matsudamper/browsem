package net.matsudamper.browser.screen.sitesettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.ui.settings.site.SiteSettingsListScreenUiState

internal class SiteSettingsListScreenViewModel(
    private val siteSettingsRepository: SiteSettingsRepository,
) : ViewModel() {
    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    interface Event {
        fun navigateToSiteSettings(host: String)
    }

    private val queryFlow = MutableStateFlow("")

    private val callbacks = object : SiteSettingsListScreenUiState.Callbacks {
        override fun setQuery(query: String) {
            queryFlow.value = query
        }

        override fun openSiteSettings(host: String) {
            eventHandler.trySend { it.navigateToSiteSettings(host) }
        }
    }

    val uiState: StateFlow<SiteSettingsListScreenUiState> = MutableStateFlow(
        SiteSettingsListScreenUiState(
            callbacks = callbacks,
            query = "",
            hosts = listOf(),
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            combine(
                siteSettingsRepository.siteHosts(),
                queryFlow,
            ) { hosts, query ->
                val normalizedQuery = query.trim()
                SiteSettingsListScreenUiState(
                    callbacks = callbacks,
                    query = query,
                    hosts = if (normalizedQuery.isEmpty()) {
                        hosts
                    } else {
                        hosts.filter { host -> host.contains(normalizedQuery, ignoreCase = true) }
                    },
                )
            }.collectLatest { state ->
                uiStateFlow.value = state
            }
        }
    }.asStateFlow()
}
