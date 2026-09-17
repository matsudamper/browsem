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
    val geminiNanoAvailable: Boolean,
    val geminiNanoModels: List<GeminiNanoModel>,
    /** 空文字は自動選択 */
    val selectedGeminiNanoModelName: String,
    val enableThirdPartyCa: Boolean,
    val enableWebSuggestions: Boolean,
    val inputAutoZoomEnabled: Boolean,
    val extensionsProcessEnabled: Boolean,
    val webAuthnPlatformAuthenticatorAvailableOverrideEnabled: Boolean,
    val mockLocationInput: String,
    val mockLocationInputError: String?,
    val backupConfirmDialog: BackupConfirmType?,
    val extensionsProcessRestartDialog: Boolean,
    val showDefaultBrowserBanner: Boolean,
) {
    enum class BackupConfirmType { Export, Import }

    /** [name] は ML Kit が返す表示モデル名で、設定の保存キーにもなる */
    @Stable
    data class GeminiNanoModel(
        val name: String,
        val downloaded: Boolean,
    )

    interface Callbacks {
        fun setHomepageType(type: HomepageType)
        fun setCustomHomepageUrl(url: String)
        fun setSearchProvider(provider: SearchProvider)
        fun setCustomSearchUrl(url: String)
        fun setThemeMode(mode: ThemeMode)
        fun setTranslationProvider(provider: TranslationProvider)

        /** 空文字で自動選択に戻す */
        fun setGeminiNanoModelName(modelName: String)

        fun setEnableThirdPartyCa(enabled: Boolean)
        fun setEnableWebSuggestions(enabled: Boolean)
        fun setInputAutoZoomEnabled(enabled: Boolean)
        fun setExtensionsProcessEnabled(enabled: Boolean)
        fun setWebAuthnPlatformAuthenticatorAvailableOverrideEnabled(enabled: Boolean)
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
