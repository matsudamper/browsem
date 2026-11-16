package net.matsudamper.browser.translate

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.google.mlkit.genai.common.FeatureStatus
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.feature.translatorapi.TranslatorApiEngine

/** Translator API のポリフィルを、設定で選ばれた Gemini Nano のモデルで実現する */
internal class GeminiNanoTranslatorApiEngine(
    private val settingsRepository: SettingsRepository,
) : TranslatorApiEngine {
    private val loadedInferenceMutex = Mutex()

    // モデルを開いたままにするとメモリを占有するため、直近に使った 1 組だけを保持する
    private var loadedInference: LoadedInference? = null

    override suspend fun availability(
        languagePair: TranslatorApiEngine.LanguagePair,
    ): TranslatorApiEngine.Availability {
        val selectedModel = GeminiNanoModel.select(savedModelKey())
            ?: return TranslatorApiEngine.Availability.UNAVAILABLE
        return try {
            if (selectedModel.generativeModel.checkStatus() == FeatureStatus.AVAILABLE) {
                TranslatorApiEngine.Availability.AVAILABLE
            } else {
                TranslatorApiEngine.Availability.DOWNLOADABLE
            }
        } finally {
            selectedModel.generativeModel.close()
        }
    }

    override suspend fun prepare(languagePair: TranslatorApiEngine.LanguagePair) {
        loadedInferenceMutex.withLock {
            loadInference(languagePair)
        }
    }

    override suspend fun translate(languagePair: TranslatorApiEngine.LanguagePair, text: String): String {
        return loadedInferenceMutex.withLock {
            loadInference(languagePair).translateText(text)
        }
    }

    /** [loadedInferenceMutex] を保持した状態で呼ぶこと */
    private suspend fun loadInference(languagePair: TranslatorApiEngine.LanguagePair): GeminiNanoInference {
        val target = InferenceTarget(savedModelKey(), languagePair)
        val reusableInference = loadedInference?.takeIf { it.target == target }
        if (reusableInference != null) return reusableInference.inference

        loadedInference?.inference?.close()
        loadedInference = null
        val selectedModel = GeminiNanoModel.select(target.savedModelKey)
            ?: throw TranslatorApiEngine.UnsupportedLanguagePairException(languagePair)
        val inference = GeminiNanoInference(
            generativeModel = selectedModel.generativeModel,
            sourceLanguage = languagePair.sourceLanguage,
            targetLanguage = languagePair.targetLanguage,
        )
        try {
            inference.prepare()
        } catch (error: Exception) {
            inference.close()
            throw error
        }
        loadedInference = LoadedInference(target, inference)
        return inference
    }

    private suspend fun savedModelKey(): String = settingsRepository.settings.first().geminiNanoModelKey

    private data class InferenceTarget(
        val savedModelKey: String,
        val languagePair: TranslatorApiEngine.LanguagePair,
    )

    private class LoadedInference(
        val target: InferenceTarget,
        val inference: GeminiNanoInference,
    )
}
