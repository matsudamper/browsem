package net.matsudamper.browser.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.feature.mocklocation.MockLocationWebExtension
import net.matsudamper.browser.data.BrowserSettings
import net.matsudamper.browser.data.HomepageType
import net.matsudamper.browser.data.SearchProvider
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.resolvedEnableWebSuggestions
import net.matsudamper.browser.data.resolvedExtensionsProcessEnabled
import net.matsudamper.browser.ui.settings.SettingsScreenUiState

internal class SettingsScreenViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val viewModelStateFlow = MutableStateFlow(ViewModelState())
    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    // リポジトリ値による初回セットを完了したかどうかのフラグ
    // isEmpty() では空文字入力と未初期化を区別できないため専用フラグを使用する
    private var mockLocationInputInitialized = false

    private val callbacks = object : SettingsScreenUiState.Callbacks {
        override fun setHomepageType(type: HomepageType) {
            viewModelScope.launch { settingsRepository.setHomepageType(type) }
        }

        override fun setCustomHomepageUrl(url: String) {
            viewModelScope.launch { settingsRepository.setCustomHomepageUrl(url) }
        }

        override fun setSearchProvider(provider: SearchProvider) {
            viewModelScope.launch { settingsRepository.setSearchProvider(provider) }
        }

        override fun setCustomSearchUrl(url: String) {
            viewModelScope.launch { settingsRepository.setCustomSearchUrl(url) }
        }

        override fun setThemeMode(mode: ThemeMode) {
            viewModelScope.launch { settingsRepository.setThemeMode(mode) }
        }

        override fun setTranslationProvider(provider: TranslationProvider) {
            viewModelScope.launch { settingsRepository.setTranslationProvider(provider) }
        }

        override fun setEnableThirdPartyCa(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setEnableThirdPartyCa(enabled) }
        }

        override fun setEnableWebSuggestions(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setEnableWebSuggestions(enabled) }
        }

        override fun setExtensionsProcessEnabled(enabled: Boolean) {
            viewModelStateFlow.update {
                it.copy(
                    pendingExtensionsProcessEnabled = enabled,
                    extensionsProcessRestartDialog = true,
                )
            }
        }

        override fun confirmExtensionsProcessRestart() {
            val enabled = viewModelStateFlow.value.pendingExtensionsProcessEnabled ?: return
            viewModelStateFlow.update {
                it.copy(
                    extensionsProcessRestartDialog = false,
                    pendingExtensionsProcessEnabled = null,
                )
            }
            viewModelScope.launch {
                settingsRepository.setExtensionsProcessEnabled(enabled)
                eventHandler.trySend { it.onRestartProcess() }
            }
        }

        override fun dismissExtensionsProcessRestartDialog() {
            viewModelStateFlow.update {
                it.copy(
                    extensionsProcessRestartDialog = false,
                    pendingExtensionsProcessEnabled = null,
                )
            }
        }

        override fun setMockLocationInput(input: String) {
            viewModelStateFlow.update { it.copy(mockLocationInput = input) }
            val parsed = parseMockLocationInput(input) ?: return
            viewModelScope.launch {
                settingsRepository.setMockLocationCoordinates(parsed.first, parsed.second)
            }
        }

        override fun openMockLocationOnMap() {
            eventHandler.trySend { it.onOpenMockLocationOnMap() }
        }

        override fun requestBackupExport() {
            viewModelStateFlow.update {
                it.copy(backupConfirmDialog = SettingsScreenUiState.BackupConfirmType.Export)
            }
        }

        override fun requestBackupImport() {
            viewModelStateFlow.update {
                it.copy(backupConfirmDialog = SettingsScreenUiState.BackupConfirmType.Import)
            }
        }

        override fun confirmBackup() {
            val type = viewModelStateFlow.value.backupConfirmDialog ?: return
            viewModelStateFlow.update { it.copy(backupConfirmDialog = null) }
            eventHandler.trySend {
                it.onNavigateToBackupProgress(type == SettingsScreenUiState.BackupConfirmType.Import)
            }
        }

        override fun dismissBackupConfirm() {
            viewModelStateFlow.update { it.copy(backupConfirmDialog = null) }
        }

        override fun openDefaultBrowserSettings() {
            eventHandler.trySend { it.onOpenDefaultBrowserSettings() }
        }
    }

    /** デフォルトブラウザの状態を UI 側に問い合わせる。結果は onDefaultBrowserStatusChecked で受け取る */
    fun refreshDefaultBrowserStatus() {
        eventHandler.trySend { it.onCheckDefaultBrowserStatus() }
    }

    fun onDefaultBrowserStatusChecked(isDefaultBrowser: Boolean) {
        viewModelStateFlow.update { it.copy(showDefaultBrowserBanner = !isDefaultBrowser) }
    }

    val uiState: StateFlow<SettingsScreenUiState?> = MutableStateFlow<SettingsScreenUiState?>(null)
        .also { uiStateFlow ->
            viewModelScope.launch {
                combine(
                    settingsRepository.settings,
                    viewModelStateFlow,
                ) { settings, state ->
                    settings to state
                }.collectLatest { (settings, state) ->
                    // 初回だけ入力欄をリポジトリの値で初期化する
                    if (!mockLocationInputInitialized) {
                        viewModelStateFlow.update {
                            it.copy(
                                mockLocationInput = formatMockLocationInput(
                                    settings.mockLocationLatitude,
                                    settings.mockLocationLongitude,
                                ),
                            )
                        }
                        mockLocationInputInitialized = true
                        return@collectLatest
                    }
                    uiStateFlow.update {
                        settings.toUiState(
                            callbacks = callbacks,
                            mockLocationInput = state.mockLocationInput,
                            backupConfirmDialog = state.backupConfirmDialog,
                            extensionsProcessRestartDialog = state.extensionsProcessRestartDialog,
                            showDefaultBrowserBanner = state.showDefaultBrowserBanner,
                        )
                    }
                    // 拡張機能への反映は BrowserViewModel が設定の Flow を監視して行う
                }
            }
        }.asStateFlow()

    interface Event {
        fun onOpenMockLocationOnMap()
        /** バックアップ進行画面に遷移する */
        fun onNavigateToBackupProgress(isImport: Boolean)
        /** 拡張プロセス設定変更のためプロセスを再起動する */
        fun onRestartProcess()
        /** デフォルトブラウザの設定画面を開く */
        fun onOpenDefaultBrowserSettings()
        /** デフォルトブラウザかどうかを UI 側で確認する */
        fun onCheckDefaultBrowserStatus()
    }

    data class ViewModelState(
        val mockLocationInput: String = "",
        val backupConfirmDialog: SettingsScreenUiState.BackupConfirmType? = null,
        val extensionsProcessRestartDialog: Boolean = false,
        val pendingExtensionsProcessEnabled: Boolean? = null,
        val showDefaultBrowserBanner: Boolean = false,
    )
}

/** "緯度,経度" 形式の文字列を (latitude, longitude) にパースする。不正な場合は null */
internal fun parseMockLocationInput(input: String): Pair<Double, Double>? {
    val parts = input.split(",")
    if (parts.size != 2) return null
    val lat = parts[0].trim().toDoubleOrNull() ?: return null
    val lng = parts[1].trim().toDoubleOrNull() ?: return null
    if (!lat.isFinite()) return null
    if (!lng.isFinite()) return null
    if (lat < -90.0 || lat > 90.0) return null
    if (lng < -180.0 || lng > 180.0) return null
    return lat to lng
}

/** バリデーションエラーメッセージを返す。問題なければ null */
internal fun validateMockLocationInput(input: String): String? {
    if (input.isBlank()) return null
    val parts = input.split(",")
    if (parts.size != 2) return "「緯度,経度」の形式で入力してください"
    val lat = parts[0].trim().toDoubleOrNull()
        ?: return "緯度が数値ではありません"
    val lng = parts[1].trim().toDoubleOrNull()
        ?: return "経度が数値ではありません"
    if (!lat.isFinite()) return "緯度が有効な数値ではありません"
    if (!lng.isFinite()) return "経度が有効な数値ではありません"
    if (lat < -90.0 || lat > 90.0) return "緯度は -90 〜 90 の範囲で入力してください"
    if (lng < -180.0 || lng > 180.0) return "経度は -180 〜 180 の範囲で入力してください"
    return null
}

/** 未保存（0.0, 0.0）の場合にデフォルト座標へ解決する */
internal fun resolveCoordinates(latitude: Double, longitude: Double): Pair<Double, Double> {
    return if (latitude == 0.0 && longitude == 0.0) {
        MockLocationWebExtension.DEFAULT_LATITUDE to MockLocationWebExtension.DEFAULT_LONGITUDE
    } else {
        latitude to longitude
    }
}

internal fun formatMockLocationInput(latitude: Double, longitude: Double): String {
    val (lat, lng) = resolveCoordinates(latitude, longitude)
    return "$lat,$lng"
}

private fun BrowserSettings.toUiState(
    callbacks: SettingsScreenUiState.Callbacks,
    mockLocationInput: String,
    backupConfirmDialog: SettingsScreenUiState.BackupConfirmType?,
    extensionsProcessRestartDialog: Boolean,
    showDefaultBrowserBanner: Boolean,
): SettingsScreenUiState {
    return SettingsScreenUiState(
        callbacks = callbacks,
        homepageType = homepageType,
        customHomepageUrl = customHomepageUrl,
        searchProvider = searchProvider,
        customSearchUrl = customSearchUrl,
        themeMode = themeMode,
        translationProvider = translationProvider,
        enableThirdPartyCa = enableThirdPartyCa,
        enableWebSuggestions = resolvedEnableWebSuggestions(),
        extensionsProcessEnabled = resolvedExtensionsProcessEnabled(),
        mockLocationInput = mockLocationInput,
        mockLocationInputError = validateMockLocationInput(mockLocationInput),
        backupConfirmDialog = backupConfirmDialog,
        extensionsProcessRestartDialog = extensionsProcessRestartDialog,
        showDefaultBrowserBanner = showDefaultBrowserBanner,
    )
}
