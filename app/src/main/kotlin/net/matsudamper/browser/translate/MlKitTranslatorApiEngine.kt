package net.matsudamper.browser.translate

import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator as MlKitTranslator
import com.google.mlkit.nl.translate.TranslatorOptions
import net.matsudamper.browser.feature.translatorapi.TranslatorApiEngine

/** Translator API のポリフィルを ML Kit のオンデバイス翻訳で実現する */
internal class MlKitTranslatorApiEngine : TranslatorApiEngine {
    private val loadedTranslatorMutex = Mutex()

    // 翻訳モデルは 1 言語ペアで数十MBのメモリを使うため、直近に使った 1 組だけを保持する
    private var loadedTranslator: LoadedTranslator? = null

    override suspend fun availability(
        languagePair: TranslatorApiEngine.LanguagePair,
    ): TranslatorApiEngine.Availability {
        val mlKitLanguagePair = languagePair.toMlKitLanguagePair()
            ?: return TranslatorApiEngine.Availability.UNAVAILABLE
        return if (isEveryModelDownloaded(mlKitLanguagePair)) {
            TranslatorApiEngine.Availability.AVAILABLE
        } else {
            TranslatorApiEngine.Availability.DOWNLOADABLE
        }
    }

    override suspend fun prepare(languagePair: TranslatorApiEngine.LanguagePair) {
        loadedTranslatorMutex.withLock {
            loadTranslator(languagePair, ModelDownload.ALLOWED)
        }
    }

    override suspend fun translate(languagePair: TranslatorApiEngine.LanguagePair, text: String): String {
        return loadedTranslatorMutex.withLock {
            val translator = loadTranslator(languagePair, ModelDownload.FORBIDDEN)
            // ML Kit は改行を保持しないため、行ごとに翻訳して元の行構造へ戻す
            text.split("\n")
                .map { line ->
                    if (line.isBlank()) line else translator.translate(line).await()
                }
                .joinToString("\n")
        }
    }

    override suspend fun detectLanguages(text: String): List<TranslatorApiEngine.DetectedLanguage> {
        return detectLanguagesWithMlKit(text)
    }

    /** [loadedTranslatorMutex] を保持した状態で呼ぶこと */
    private suspend fun loadTranslator(
        languagePair: TranslatorApiEngine.LanguagePair,
        modelDownload: ModelDownload,
    ): MlKitTranslator {
        val mlKitLanguagePair = languagePair.toMlKitLanguagePair()
            ?: throw TranslatorApiEngine.UnsupportedLanguagePairException(languagePair)
        val reusableTranslator = loadedTranslator?.takeIf { it.languagePair == mlKitLanguagePair }
        if (reusableTranslator != null) return reusableTranslator.translator

        if (modelDownload == ModelDownload.FORBIDDEN && !isEveryModelDownloaded(mlKitLanguagePair)) {
            throw TranslatorApiEngine.ModelNotPreparedException()
        }
        loadedTranslator?.translator?.close()
        loadedTranslator = null
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(mlKitLanguagePair.sourceLanguage)
                .setTargetLanguage(mlKitLanguagePair.targetLanguage)
                .build(),
        )
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        } catch (error: Exception) {
            translator.close()
            throw error
        }
        loadedTranslator = LoadedTranslator(mlKitLanguagePair, translator)
        return translator
    }

    private suspend fun isEveryModelDownloaded(languagePair: MlKitLanguagePair): Boolean {
        val remoteModelManager = RemoteModelManager.getInstance()
        return listOf(languagePair.sourceLanguage, languagePair.targetLanguage).all { language ->
            remoteModelManager.isModelDownloaded(TranslateRemoteModel.Builder(language).build()).await()
        }
    }

    private enum class ModelDownload {
        ALLOWED,
        FORBIDDEN,
    }

    private data class MlKitLanguagePair(
        val sourceLanguage: String,
        val targetLanguage: String,
    )

    private class LoadedTranslator(
        val languagePair: MlKitLanguagePair,
        val translator: MlKitTranslator,
    )

    /** ML Kit が対応していない言語、または同一言語の組み合わせなら null */
    private fun TranslatorApiEngine.LanguagePair.toMlKitLanguagePair(): MlKitLanguagePair? {
        val source = toTranslateLanguage(sourceLanguage) ?: return null
        val target = toTranslateLanguage(targetLanguage) ?: return null
        if (source == target) return null
        return MlKitLanguagePair(sourceLanguage = source, targetLanguage = target)
    }

    private fun toTranslateLanguage(languageTag: String): String? {
        val normalized = languageTag.lowercase(Locale.ROOT)
        return TranslateLanguage.fromLanguageTag(normalized)
            ?: TranslateLanguage.fromLanguageTag(normalized.substringBefore('-'))
    }
}
