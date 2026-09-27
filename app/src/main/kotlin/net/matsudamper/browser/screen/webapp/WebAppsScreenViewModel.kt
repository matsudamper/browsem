package net.matsudamper.browser.screen.webapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.matsudamper.browser.AddToHomeScreenTarget
import net.matsudamper.browser.WebAppShortcutManager
import net.matsudamper.browser.data.ProfileData
import net.matsudamper.browser.data.ProfileId
import net.matsudamper.browser.data.ProfileRepository
import net.matsudamper.browser.data.WebAppData
import net.matsudamper.browser.data.WebAppRepository
import net.matsudamper.browser.requestPinRegisteredWebAppToHome
import net.matsudamper.browser.ui.settings.webapp.WebAppsScreenUiState

internal class WebAppsScreenViewModel(
    private val webAppRepository: WebAppRepository,
    private val profileRepository: ProfileRepository,
    private val webAppShortcutManager: WebAppShortcutManager,
) : ViewModel() {
    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    private val viewModelStateFlow = MutableStateFlow(ViewModelState())

    private val deleteConfirmDialogListener = object : WebAppsScreenUiState.DeleteConfirmDialog.Listener {
        override fun onConfirm() {
            val target = viewModelStateFlow.value.deleteTarget ?: return
            viewModelStateFlow.update { it.copy(deleteTarget = null) }
            // 確定直後に画面を閉じても、アイコンの無効化と DB 削除の途中で止めて登録だけ残さないよう、キャンセルさせない
            viewModelScope.launch(NonCancellable) {
                val isDisabled = withContext(Dispatchers.IO) {
                    webAppShortcutManager.disableShortcut(webAppId = target.id, title = target.title)
                }
                if (isDisabled) {
                    webAppRepository.deleteWebApp(target.id)
                } else {
                    eventHandler.trySend { it.onDeleteFailed() }
                }
            }
        }

        override fun onDismiss() {
            viewModelStateFlow.update { it.copy(deleteTarget = null) }
        }
    }

    private val renameDialogListener = object : WebAppsScreenUiState.RenameDialog.Listener {
        override fun onConfirm(title: String) {
            val target = viewModelStateFlow.value.renameTarget ?: return
            viewModelStateFlow.update { it.copy(renameTarget = null) }
            // 確定直後に画面を閉じても、アイコンと DB の名前が食い違ったまま止まらないよう、キャンセルさせない
            viewModelScope.launch(NonCancellable) {
                // 一覧の名前とホームのアイコンの名前を食い違わせないよう、アイコンを書き換えられたときだけ名前を変える
                val isLabelUpdated = withContext(Dispatchers.IO) {
                    webAppShortcutManager.updateLabel(webAppId = target.id, label = title)
                }
                if (isLabelUpdated) {
                    webAppRepository.renameWebApp(target.id, title)
                } else {
                    eventHandler.trySend { it.onRenameFailed() }
                }
            }
        }

        override fun onDismiss() {
            viewModelStateFlow.update { it.copy(renameTarget = null) }
        }
    }

    val uiState: StateFlow<WebAppsScreenUiState> = MutableStateFlow(
        WebAppsScreenUiState(
            isLoading = true,
            entries = listOf(),
            deleteConfirmDialog = null,
            renameDialog = null,
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
                        renameDialog = state.renameTarget?.let { target ->
                            WebAppsScreenUiState.RenameDialog(
                                currentTitle = target.title,
                                listener = renameDialogListener,
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
                override fun onClick() {
                    viewModelStateFlow.update { it.copy(renameTarget = webApp) }
                }

                override fun onClickAddToHome() {
                    val target = AddToHomeScreenTarget.RegisteredWebApp(
                        webAppId = webApp.id,
                        url = webApp.startUrl,
                        title = webApp.title,
                        profileId = webApp.profileId,
                    )
                    val isPinned = requestPinRegisteredWebAppToHome(
                        webAppShortcutManager = webAppShortcutManager,
                        target = target,
                    )
                    if (!isPinned) {
                        eventHandler.trySend { it.onPinNotSupported() }
                    }
                }

                override fun onClickDelete() {
                    viewModelStateFlow.update { it.copy(deleteTarget = webApp) }
                }
            },
        )
    }

    interface Event {
        /** ランチャーがピン留めに対応していない */
        fun onPinNotSupported()
        fun onRenameFailed()

        /** ランチャーがアイコンの書き換えを拒否したため、削除を中止した（レート制限など） */
        fun onDeleteFailed()
    }

    data class ViewModelState(
        val isLoading: Boolean = true,
        val webApps: List<WebAppData> = listOf(),
        val profiles: List<ProfileData> = listOf(),
        val deleteTarget: WebAppData? = null,
        val renameTarget: WebAppData? = null,
    )

    private companion object {
        private val PIN_CONFIRMATION_GRACE_MILLIS = TimeUnit.MINUTES.toMillis(10)
    }
}
