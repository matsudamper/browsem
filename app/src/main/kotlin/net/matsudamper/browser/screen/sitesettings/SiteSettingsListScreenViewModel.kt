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
    companion object {
        private const val PAGE_SIZE = 50
    }

    private data class SearchState(
        val query: String,
        val loadedPageCount: Int,
    )

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    interface Event {
        fun navigateToSiteSettings(host: String)
    }

    private val searchStateFlow = MutableStateFlow(
        SearchState(
            query = "",
            loadedPageCount = 1,
        ),
    )

    private val callbacks = object : SiteSettingsListScreenUiState.Callbacks {
        override fun setQuery(query: String) {
            searchStateFlow.value = SearchState(
                query = query,
                loadedPageCount = 1,
            )
        }

        override fun loadNextPage() {
            val current = searchStateFlow.value
            searchStateFlow.value = current.copy(
                loadedPageCount = current.loadedPageCount + 1,
            )
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
            hasNextPage = false,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            combine(
                siteSettingsRepository.siteHosts(),
                searchStateFlow,
            ) { hosts, searchState ->
                val normalizedQuery = searchState.query.trim()
                val filteredHosts = if (normalizedQuery.isEmpty()) {
                    hosts
                } else {
                    hosts.filter { host -> host.contains(normalizedQuery, ignoreCase = true) }
                }
                val loadedHostCount = searchState.loadedPageCount * PAGE_SIZE
                SiteSettingsListScreenUiState(
                    callbacks = callbacks,
                    query = searchState.query,
                    hosts = filteredHosts.take(loadedHostCount),
                    hasNextPage = filteredHosts.size > loadedHostCount,
                )
            }.collectLatest { state ->
                uiStateFlow.value = state
            }
        }
    }.asStateFlow()
}
