package net.matsudamper.browser.ui.settings

import androidx.compose.runtime.Stable
import net.matsudamper.browser.data.HomepageType
import net.matsudamper.browser.data.SearchProvider
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.data.TranslationProvider

@Stable
data class SettingsScreenUiState(
    val callbacks: Callbacks,
    val homepageType: HomepageType,
    val customHomepageUrl: String,
    val searchProvider: SearchProvider,
    val customSearchUrl: String,
    val themeMode: ThemeMode,
    val translationProvider: TranslationProvider,
    val enableThirdPartyCa: Boolean,
    val enableWebSuggestions: Boolean,
    val inputAutoZoomEnabled: Boolean,
    val extensionsProcessEnabled: Boolean,
    val mockLocationInput: String,
    val mockLocationInputError: String?,
    val backupConfirmDialog: BackupConfirmType?,
    val extensionsProcessRestartDialog: Boolean,
    val showDefaultBrowserBanner: Boolean,
) {
    enum class BackupConfirmType { Export, Import }

    interface Callbacks {
        fun setHomepageType(type: HomepageType)
        fun setCustomHomepageUrl(url: String)
        fun setSearchProvider(provider: SearchProvider)
        fun setCustomSearchUrl(url: String)
        fun setThemeMode(mode: ThemeMode)
        fun setTranslationProvider(provider: TranslationProvider)
        fun setEnableThirdPartyCa(enabled: Boolean)
        fun setEnableWebSuggestions(enabled: Boolean)
        fun setInputAutoZoomEnabled(enabled: Boolean)
        fun setExtensionsProcessEnabled(enabled: Boolean)
        fun confirmExtensionsProcessRestart()
        fun dismissExtensionsProcessRestartDialog()
        fun setMockLocationInput(input: String)
        fun openMockLocationOnMap()
        /** バックアップのエクスポートを要求する（確認ダイアログを表示） */
        fun requestBackupExport()
        /** バックアップのインポートを要求する（確認ダイアログを表示） */
        fun requestBackupImport()
        /** 確認ダイアログで「開始」を押した */
        fun confirmBackup()
        /** 確認ダイアログを閉じる */
        fun dismissBackupConfirm()
        /** デフォルトブラウザの設定画面を開く */
        fun openDefaultBrowserSettings()
    }
}
