package net.matsudamper.browser.screen.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import net.matsudamper.browser.MockLocationWebExtension
import net.matsudamper.browser.data.BackupRepository
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
    private val backupRepository: BackupRepository,
) : ViewModel() {

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    // 設定入力中の一時的な文字列（バリデーション前）
    private val mockLocationInputFlow = MutableStateFlow("")
    // リポジトリ値による初回セットを完了したかどうかのフラグ
    // isEmpty() では空文字入力と未初期化を区別できないため専用フラグを使用する
    private var mockLocationInputInitialized = false

    private val backupStateFlow = MutableStateFlow(
        SettingsScreenUiState.BackupUiState(
            isBusy = false,
            message = null,
            pendingRestart = false,
        ),
    )
    // export/import 同時起動による状態の二重更新を防ぐ。
    // UI 側のボタン無効化と二重に守ることで、外部呼び出しからも安全にする。
    private val backupMutex = Mutex()

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
            eventHandler.trySend { it.onRequestBackupExport() }
        }

        override fun requestBackupImport() {
            eventHandler.trySend { it.onRequestBackupImport() }
        }

        override fun consumeBackupMessage() {
            backupStateFlow.update { it.copy(message = null) }
        }

        override fun confirmRestartAfterImport() {
            eventHandler.trySend { it.onRestartApp() }
        }
    }

    fun exportToZip(uri: Uri) {
        viewModelScope.launch {
            if (!backupMutex.tryLock()) return@launch
            try {
                backupStateFlow.update { it.copy(isBusy = true, message = null) }
                val result = runCatching { backupRepository.exportToZip(uri) }
                backupStateFlow.update {
                    it.copy(
                        isBusy = false,
                        message = result.fold(
                            onSuccess = { "バックアップを書き出しました" },
                            onFailure = { e -> "エクスポートに失敗しました: ${e.message ?: e::class.simpleName}" },
                        ),
                    )
                }
            } finally {
                backupMutex.unlock()
            }
        }
    }

    fun importFromZip(uri: Uri) {
        viewModelScope.launch {
            if (!backupMutex.tryLock()) return@launch
            try {
                backupStateFlow.update { it.copy(isBusy = true, message = null) }
                val result = runCatching { backupRepository.importFromZip(uri) }
                backupStateFlow.update { state ->
                    result.fold(
                        onSuccess = {
                            state.copy(isBusy = false, message = null, pendingRestart = true)
                        },
                        onFailure = { e ->
                            state.copy(
                                isBusy = false,
                                message = "復元に失敗しました: ${e.message ?: e::class.simpleName}",
                            )
                        },
                    )
                }
            } finally {
                backupMutex.unlock()
            }
        }
    }

    val uiState: StateFlow<SettingsScreenUiState?> = MutableStateFlow<SettingsScreenUiState?>(null)
        .also { uiStateFlow ->
            viewModelScope.launch {
                settingsRepository.settings.collectLatest { settings ->
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
                            backup = backupStateFlow.value,
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
            // バックアップ処理の進行状況を UiState に反映する
            viewModelScope.launch {
                backupStateFlow.collectLatest { backup ->
                    val current = uiStateFlow.value ?: return@collectLatest
                    uiStateFlow.update { current.copy(backup = backup) }
                }
            }
        }.asStateFlow()

    interface Event {
        fun onOpenMockLocationOnMap()
        fun onRequestBackupExport()
        fun onRequestBackupImport()
        fun onRestartApp()
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
    backup: SettingsScreenUiState.BackupUiState,
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
        backup = backup,
    )
}
