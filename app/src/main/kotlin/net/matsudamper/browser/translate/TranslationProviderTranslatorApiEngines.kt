package net.matsudamper.browser.translate

import kotlinx.coroutines.flow.first
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.feature.translatorapi.TranslatorApiEngine

/** Translator API のポリフィルが使う翻訳エンジンを、設定された翻訳プロバイダに合わせて選ぶ */
internal class TranslationProviderTranslatorApiEngines(
    private val settingsRepository: SettingsRepository,
) {
    private val localAiEngine = MlKitTranslatorApiEngine()
    private val geminiNanoEngine = GeminiNanoTranslatorApiEngine(settingsRepository)

    /** Gecko は拡張機能側で Gecko 内蔵の翻訳エンジンを呼ぶため、アプリ側のエンジンは無く null を返す */
    suspend fun selectAppEngine(): TranslatorApiEngine? {
        return when (settingsRepository.settings.first().translationProvider) {
            TranslationProvider.TRANSLATION_PROVIDER_GECKO,
            TranslationProvider.UNRECOGNIZED,
            -> null

            TranslationProvider.TRANSLATION_PROVIDER_LOCAL_AI -> localAiEngine

            TranslationProvider.TRANSLATION_PROVIDER_GEMINI_NANO -> geminiNanoEngine
        }
    }
}
