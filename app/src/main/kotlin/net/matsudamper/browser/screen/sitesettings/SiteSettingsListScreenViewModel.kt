package net.matsudamper.browser.screen.sitesettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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

    private data class PageResult(
        val searchState: SearchState,
        val hosts: List<String>?,
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
    private var isPageLoadInProgress = false

    private val callbacks = object : SiteSettingsListScreenUiState.Callbacks {
        override fun setQuery(query: String) {
            isPageLoadInProgress = true
            searchStateFlow.value = SearchState(
                query = query,
                loadedPageCount = 1,
            )
        }

        override fun loadNextPage() {
            if (isPageLoadInProgress) return
            isPageLoadInProgress = true
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
            isLoading = true,
            hasNextPage = false,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            searchStateFlow
                .flatMapLatest(::observePage)
                .collectLatest { result ->
                    val hosts = result.hosts
                    if (hosts == null) {
                        val current = uiStateFlow.value
                        val canKeepCurrentPage = current.query == result.searchState.query
                        uiStateFlow.value = SiteSettingsListScreenUiState(
                            callbacks = callbacks,
                            query = result.searchState.query,
                            hosts = if (canKeepCurrentPage) current.hosts else listOf(),
                            isLoading = !canKeepCurrentPage || current.hosts.isEmpty(),
                            hasNextPage = if (canKeepCurrentPage) current.hasNextPage else false,
                        )
                    } else {
                        isPageLoadInProgress = false
                        val loadedHostCount = result.searchState.loadedPageCount * PAGE_SIZE
                        uiStateFlow.value = SiteSettingsListScreenUiState(
                            callbacks = callbacks,
                            query = result.searchState.query,
                            hosts = hosts.take(loadedHostCount),
                            isLoading = false,
                            hasNextPage = hosts.size > loadedHostCount,
                        )
                    }
                }
        }
    }.asStateFlow()

    private fun observePage(searchState: SearchState): Flow<PageResult> {
        val loadedHostCount = searchState.loadedPageCount * PAGE_SIZE
        return siteSettingsRepository.siteHosts(
            query = searchState.query,
            limit = loadedHostCount + 1,
            offset = 0,
        ).map { hosts ->
            PageResult(
                searchState = searchState,
                hosts = hosts,
            )
        }.onStart {
            emit(
                PageResult(
                    searchState = searchState,
                    hosts = null,
                ),
            )
        }
    }
}
