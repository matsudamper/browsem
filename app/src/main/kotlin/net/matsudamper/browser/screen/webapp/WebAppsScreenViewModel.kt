package net.matsudamper.browser.screen.webapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.matsudamper.browser.WebAppShortcutManager
import net.matsudamper.browser.data.ProfileData
import net.matsudamper.browser.data.ProfileId
import net.matsudamper.browser.data.ProfileRepository
import net.matsudamper.browser.data.WebAppData
import net.matsudamper.browser.data.WebAppRepository
import net.matsudamper.browser.ui.settings.webapp.WebAppsScreenUiState

internal class WebAppsScreenViewModel(
    private val webAppRepository: WebAppRepository,
    private val profileRepository: ProfileRepository,
    private val webAppShortcutManager: WebAppShortcutManager,
) : ViewModel() {
    private val viewModelStateFlow = MutableStateFlow(ViewModelState())

    private val deleteConfirmDialogListener = object : WebAppsScreenUiState.DeleteConfirmDialog.Listener {
        override fun onConfirm() {
            val target = viewModelStateFlow.value.deleteTarget ?: return
            viewModelStateFlow.update { it.copy(deleteTarget = null) }
            webAppShortcutManager.disableShortcut(webAppId = target.id, title = target.title)
            viewModelScope.launch { webAppRepository.deleteWebApp(target.id) }
        }

        override fun onDismiss() {
            viewModelStateFlow.update { it.copy(deleteTarget = null) }
        }
    }

    val uiState: StateFlow<WebAppsScreenUiState> = MutableStateFlow(
        WebAppsScreenUiState(
            isLoading = true,
            entries = listOf(),
            deleteConfirmDialog = null,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            viewModelStateFlow.collectLatest { state ->
                uiStateFlow.update {
                    WebAppsScreenUiState(
                        isLoading = state.isLoading,
                        entries = state.webApps.map { webApp -> toEntryItem(webApp, state.profiles) },
                        deleteConfirmDialog = state.deleteTarget?.let { target ->
                            WebAppsScreenUiState.DeleteConfirmDialog(
                                title = target.title,
                                listener = deleteConfirmDialogListener,
                            )
                        },
                    )
                }
            }
        }
    }.asStateFlow()

    init {
        viewModelScope.launch {
            removeUnpinnedWebApps()
            combine(
                webAppRepository.observeWebApps(),
                profileRepository.observeProfiles(),
            ) { webApps, profiles -> webApps to profiles }
                .collect { (webApps, profiles) ->
                    viewModelStateFlow.update {
                        it.copy(isLoading = false, webApps = webApps, profiles = profiles)
                    }
                }
        }
    }

    /**
     * ホームからアイコンを消されてもアプリには通知されないため、一覧を開いたときにピン留めと突き合わせて消す。
     * ピン留めに対応していないランチャーではピン留め一覧が常に空になり全件消えてしまうため、突き合わせない。
     *
     * 登録はピン留めの確認ダイアログを出す前に行うため、作成直後の登録はまだピン留めされていないことがある。
     * 確認中や、別ウィンドウで追加している最中の登録を消さないよう、作成から一定時間経ったものだけを対象にする。
     */
    private suspend fun removeUnpinnedWebApps() {
        if (!webAppShortcutManager.isPinSupported()) return
        val createdBefore = System.currentTimeMillis() - PIN_CONFIRMATION_GRACE_MILLIS
        val pinnedWebAppIds = withContext(Dispatchers.IO) { webAppShortcutManager.pinnedWebAppIds() }
        webAppRepository.deleteWebAppsExcept(keepWebAppIds = pinnedWebAppIds, createdBefore = createdBefore)
    }

    private fun toEntryItem(webApp: WebAppData, profiles: List<ProfileData>): WebAppsScreenUiState.EntryItem {
        // 削除済みプロファイルのアプリは起動時にデフォルトプロファイルへフォールバックするため、表示もそれに合わせる
        val profile = profiles.firstOrNull { it.id == webApp.profileId }
            ?: profiles.firstOrNull { it.id == ProfileId.DEFAULT }
        return WebAppsScreenUiState.EntryItem(
            title = webApp.title,
            startUrl = webApp.startUrl,
            profileName = profile?.name.orEmpty(),
            listener = object : WebAppsScreenUiState.EntryItem.Listener {
                override fun onClickDelete() {
                    viewModelStateFlow.update { it.copy(deleteTarget = webApp) }
                }
            },
        )
    }

    data class ViewModelState(
        val isLoading: Boolean = true,
        val webApps: List<WebAppData> = listOf(),
        val profiles: List<ProfileData> = listOf(),
        val deleteTarget: WebAppData? = null,
    )

    private companion object {
        private val PIN_CONFIRMATION_GRACE_MILLIS = TimeUnit.MINUTES.toMillis(10)
    }
}
