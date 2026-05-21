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
import net.matsudamper.browser.MockLocationWebExtension
import net.matsudamper.browser.data.BrowserSettings
import net.matsudamper.browser.data.HomepageType
import net.matsudamper.browser.data.SearchProvider
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.resolvedEnableWebSuggestions
import net.matsudamper.browser.ui.settings.SettingsScreenUiState

internal class SettingsScreenViewModel(
    private val settingsRepository: SettingsRepository,
    private val mockLocationWebExtension: MockLocationWebExtension,
) : ViewModel() {

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    // 設定入力中の一時的な文字列（バリデーション前）
    private val mockLocationInputFlow = MutableStateFlow("")
    // リポジトリ値による初回セットを完了したかどうかのフラグ
    // isEmpty() では空文字入力と未初期化を区別できないため専用フラグを使用する
    private var mockLocationInputInitialized = false

    // 確認ダイアログの表示状態
    private val backupConfirmDialogFlow =
        MutableStateFlow<SettingsScreenUiState.BackupConfirmType?>(null)

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

        override fun setMockLocationEnabled(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository.setMockLocationEnabled(enabled)
            }
        }

        override fun setMockLocationInput(input: String) {
            mockLocationInputFlow.value = input
            val parsed = parseMockLocationInput(input) ?: return
            viewModelScope.launch {
                settingsRepository.setMockLocationCoordinates(parsed.first, parsed.second)
            }
        }

        override fun openMockLocationOnMap() {
            eventHandler.trySend { it.onOpenMockLocationOnMap() }
        }

        override fun requestBackupExport() {
            backupConfirmDialogFlow.value = SettingsScreenUiState.BackupConfirmType.Export
        }

        override fun requestBackupImport() {
            backupConfirmDialogFlow.value = SettingsScreenUiState.BackupConfirmType.Import
        }

        override fun confirmBackup() {
            val type = backupConfirmDialogFlow.value ?: return
            backupConfirmDialogFlow.value = null
            eventHandler.trySend {
                it.onNavigateToBackupProgress(type == SettingsScreenUiState.BackupConfirmType.Import)
            }
        }

        override fun dismissBackupConfirm() {
            backupConfirmDialogFlow.value = null
        }
    }

    val uiState: StateFlow<SettingsScreenUiState?> = MutableStateFlow<SettingsScreenUiState?>(null)
        .also { uiStateFlow ->
            viewModelScope.launch {
                combine(
                    settingsRepository.settings,
                    backupConfirmDialogFlow,
                ) { settings, confirmDialog -> settings to confirmDialog }
                    .collectLatest { (settings, confirmDialog) ->
                    // 初回だけ入力欄をリポジトリの値で初期化する
                    if (!mockLocationInputInitialized) {
                        mockLocationInputFlow.value = formatMockLocationInput(
                            settings.mockLocationLatitude,
                            settings.mockLocationLongitude,
                        )
                        mockLocationInputInitialized = true
                    }
                    uiStateFlow.update {
                        settings.toUiState(
                            callbacks = callbacks,
                            mockLocationInput = mockLocationInputFlow.value,
                            backupConfirmDialog = confirmDialog,
                        )
                    }
                    // 拡張機能にも最新設定を通知する
                    // 座標が未保存（0,0）の場合はデフォルト値（皇居）を使用し、
                    // UI表示と拡張機能への返却値を一致させる
                    val (resolvedLat, resolvedLng) = resolveCoordinates(
                        settings.mockLocationLatitude,
                        settings.mockLocationLongitude,
                    )
                    mockLocationWebExtension.updateConfig(
                        MockLocationWebExtension.MockLocationConfig(
                            enabled = settings.mockLocationEnabled,
                            latitude = resolvedLat,
                            longitude = resolvedLng,
                        )
                    )
                }
            }
            // 入力欄が変化したら UiState を再構築する
            viewModelScope.launch {
                mockLocationInputFlow.collectLatest { input ->
                    val current = uiStateFlow.value ?: return@collectLatest
                    uiStateFlow.update {
                        current.copy(
                            mockLocationInput = input,
                            mockLocationInputError = validateMockLocationInput(input),
                        )
                    }
                }
            }
        }.asStateFlow()

    interface Event {
        fun onOpenMockLocationOnMap()
        /** バックアップ進行画面に遷移する */
        fun onNavigateToBackupProgress(isImport: Boolean)
    }
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
        mockLocationEnabled = mockLocationEnabled,
        mockLocationInput = mockLocationInput,
        mockLocationInputError = validateMockLocationInput(mockLocationInput),
        backupConfirmDialog = backupConfirmDialog,
    )
}
