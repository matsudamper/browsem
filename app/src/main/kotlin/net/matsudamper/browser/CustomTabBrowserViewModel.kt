package net.matsudamper.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.matsudamper.browser.core.TabStoreState
import net.matsudamper.browser.data.ProfileData
import net.matsudamper.browser.data.ProfileIcon
import net.matsudamper.browser.data.ProfileId
import net.matsudamper.browser.data.ProfileRepository
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.feature.media.MediaWebExtension
import net.matsudamper.browser.translate.PageTranslationWebExtension
import net.matsudamper.browser.ui.tabs.ProfileSwitcherUiState
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

/**
 * カスタムタブのタブ管理を保持する ViewModel。
 *
 * 画面回転などの構成変更で Activity が作り直されても GeckoSession を開いたまま維持する。
 * `window.open` から引き渡されたセッションは URL の再読み込みで作り直せないため、
 * Activity 側で保持すると構成変更で内容が失われる。
 *
 * カスタムタブは一時的なセッションのため、タブ状態は DB に永続化しない。
 */
internal class CustomTabBrowserViewModel(
    tabRepository: TabRepository,
    runtime: GeckoRuntime,
    handoffToken: String?,
    private val mediaWebExtension: MediaWebExtension,
    private val pageTranslationWebExtension: PageTranslationWebExtension,
    applicationScope: CoroutineScope,
    private val backgroundSessionSuspender: BackgroundSessionSuspender,
    private val profileRepository: ProfileRepository,
    private val profileDeletionNotifier: CustomTabProfileDeletionNotifier,
) : ViewModel() {
    val browserTabController = BrowserTabController(
        tabRepository = tabRepository,
        tabGroupRepository = null,
        profileRepository = profileRepository,
        isSinglePage = true,
        persistenceScope = applicationScope,
    )
    val browserSessionLifecycleController = BrowserSessionLifecycleController(runtime)

    // 取り出したセッションはタブへ載せるまでどこからも参照されない。設定の読み込み待ちなどで
    // 載せる前に画面が終わると、開いたままのセッションと opener の保持が残るため、
    // 載せるまでの間はこの ViewModel が持ち主になる。
    private var pendingHandoff = handoffToken?.let { WindowOpenHandoffStore.consume(it) }

    /** 引き渡しを受け取れたか。載せ終えた後も、構成変更で作り直された画面から見て変わらない。 */
    val hasHandoff: Boolean = pendingHandoff != null
    val handoffTabId: String? = pendingHandoff?.tabId

    val handoffSession: GeckoSession?
        get() = pendingHandoff?.session

    /**
     * タブへ載せる URL。載せるまでに遷移していれば要求時の URL ではなく遷移先を使うため、
     * 載せる直前に読む必要がある。
     */
    fun currentHandoffInitialUrl(): String? {
        return pendingHandoff?.let { it.holdingDelegate.latestLocation ?: it.initialUrl }
    }

    /**
     * タブへ載せ終えたことを記録し、載せる前に window.close が呼ばれていたかを返す。
     *
     * 暫定 delegate は起動元の Activity を捕まえているため、載せたら参照を手放す。
     */
    fun onHandoffSessionAttached(): Boolean {
        val closeRequested = pendingHandoff?.holdingDelegate?.isCloseRequested == true
        pendingHandoff = null
        return closeRequested
    }

    private val onProfileDeleted: suspend (ProfileId) -> Unit = { deletedProfileId ->
        moveTabsOffDeletedProfile(deletedProfileId)
    }

    private val profileCallbacks = object : ProfileSwitcherUiState.Callbacks {
        override fun onAddProfile() {
            addProfile()
        }
    }

    /**
     * 履歴サジェストに使うプロファイル。
     * タブがあるときはその contextId を使い、移動や引き渡されたポップアップでも本体の有効プロファイルには寄せない。
     * タブを載せる前だけ、これから開く有効プロファイルに合わせる。
     */
    val displayedProfileId: StateFlow<ProfileId> = combine(
        browserTabController.tabStoreState,
        profileRepository.observeProfiles(),
    ) { store, profiles ->
        currentProfileId(store, profiles)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ProfileId.DEFAULT,
    )

    /**
     * カスタムタブのメニューに出すプロファイル。
     * 強調するのはこのタブの contextId で、本体ブラウザの有効プロファイルとは別に持つ。
     */
    val profileSwitcher: StateFlow<ProfileSwitcherUiState> = combine(
        profileRepository.observeProfiles(),
        profileRepository.observeTabCounts(),
        browserTabController.tabStoreState,
    ) { profiles, tabCounts, store ->
        buildProfileSwitcher(
            profiles = profiles,
            tabCounts = tabCounts,
            currentProfileId = currentProfileId(store, profiles),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = buildProfileSwitcher(
            profiles = listOf(),
            tabCounts = mapOf(),
            currentProfileId = ProfileId.DEFAULT,
        ),
    )

    init {
        profileDeletionNotifier.addListener(onProfileDeleted)
        viewModelScope.launch {
            profileRepository.createDefaultProfileIfEmpty()
        }
        browserTabController.onTabListChanged = {
            browserSessionLifecycleController.retainOpenersOfLivePopups(
                tabs = browserTabController.tabs,
                selectedTabId = browserTabController.selectedTabId,
            )
        }
        // ページ翻訳ブリッジは content script より先に MessageDelegate が要る。設定画面などで
        // タブの Composition が外れても外さないよう、セッションの寿命に合わせて張る。
        browserTabController.onTabSessionCreated = { session ->
            pageTranslationWebExtension.registerSession(session)
        }
        browserTabController.onTabSessionDisposed = { session ->
            mediaWebExtension.releaseSession(session)
            pageTranslationWebExtension.unregisterSession(session)
        }
        trackBackgroundSessions(
            suspender = backgroundSessionSuspender,
            browserTabController = browserTabController,
            lifecycleController = browserSessionLifecycleController,
        )
    }

    /**
     * このカスタムタブを指定プロファイルで開き直す。
     * GeckoSession の contextId は open 後に変えられないため、同じ URL のセッションを作り直す。
     * 本体ブラウザの有効プロファイルは変えない。
     */
    fun moveTabToProfile(tabId: String, url: String, profileId: ProfileId) {
        viewModelScope.launch {
            moveTabToProfileNow(tabId, url, profileId)
        }
    }

    private suspend fun moveTabToProfileNow(tabId: String, url: String, profileId: ProfileId) {
        val tab = browserTabController.findTab(tabId) ?: return
        if (ProfileId.fromGeckoContextId(tab.session.settings.contextId) == profileId) return
        val pageUrl = url.ifBlank { tab.currentUrl }.ifBlank { "about:blank" }
        val newTab = browserTabController.createAndAppendTab(
            initialUrl = pageUrl,
            profileId = profileId,
            insertAfterSelectedTab = false,
        )
        browserTabController.selectTab(newTab.tabId)
        browserTabController.closeTabWithUndo(tabId, nextSelectedTabId = newTab.tabId)
        browserTabController.confirmClosedTab()
    }

    private fun buildProfileSwitcher(
        profiles: List<ProfileData>,
        tabCounts: Map<ProfileId, Int>,
        currentProfileId: ProfileId,
    ): ProfileSwitcherUiState {
        val currentProfile = profiles.firstOrNull { it.id == currentProfileId }
        return ProfileSwitcherUiState(
            activeProfileIcon = currentProfile?.icon ?: ProfileIcon.PERSON,
            profiles = profiles.map { profile ->
                ProfileSwitcherUiState.ProfileItem(
                    id = profile.id,
                    name = profile.name,
                    icon = profile.icon,
                    tabCount = tabCounts[profile.id] ?: 0,
                    isActive = profile.id == currentProfileId,
                    // 削除は他画面のセッションも閉じる必要がある。このコントローラーだけでは止められない
                    isDeletable = false,
                    listener = object : ProfileSwitcherUiState.ProfileItem.Listener {
                        override fun onSelect() {
                            moveSelectedTabToProfile(profile.id)
                        }

                        override fun onRename(newName: String) {
                            viewModelScope.launch {
                                profileRepository.renameProfile(profile.id, newName)
                            }
                        }

                        override fun onChangeIcon(icon: ProfileIcon) {
                            viewModelScope.launch {
                                profileRepository.updateProfileIcon(profile.id, icon)
                            }
                        }

                        override fun onDelete() = Unit
                    },
                )
            },
            callbacks = profileCallbacks,
        )
    }

    private fun currentProfileId(
        store: TabStoreState,
        profiles: List<ProfileData>,
    ): ProfileId {
        val tabProfileId = store.selectedTabId?.let { selectedId ->
            store.tabs.firstOrNull { it.id == selectedId }?.profileId
        }
        if (tabProfileId != null) return ProfileId(tabProfileId)
        return profiles.firstOrNull { it.isActive }?.id ?: ProfileId.DEFAULT
    }

    /**
     * 削除されたプロファイルのセッションをデフォルトへ開き直す。
     * contextId は差し替えられないため、サイトデータ削除の前にこのセッションを手放す。
     */
    private suspend fun moveTabsOffDeletedProfile(profileId: ProfileId) {
        val targets = browserTabController.tabs
            .filter { ProfileId.fromGeckoContextId(it.session.settings.contextId) == profileId }
            .map { it.tabId to it.currentUrl }
        targets.forEach { (tabId, url) ->
            moveTabToProfileNow(tabId, url, ProfileId.DEFAULT)
        }
    }

    private fun moveSelectedTabToProfile(profileId: ProfileId) {
        val tab = browserTabController.tabs.firstOrNull { it.tabId == browserTabController.selectedTabId }
            ?: browserTabController.tabs.firstOrNull()
            ?: return
        moveTabToProfile(tab.tabId, tab.currentUrl, profileId)
    }

    private fun addProfile() {
        viewModelScope.launch {
            val profileCount = profileRepository.observeProfiles().first().size
            profileRepository.addProfile(
                name = "プロファイル ${profileCount + 1}",
                icon = ProfileIcon.PERSON,
                sortOrder = profileCount,
            )
        }
    }

    override fun onCleared() {
        profileDeletionNotifier.removeListener(onProfileDeleted)
        pendingHandoff?.session?.let { session ->
            HandedOffPopupRegistry.unregister(session)
            if (session.isOpen) {
                session.close()
            }
        }
        pendingHandoff = null
        browserTabController.close()
    }
}
