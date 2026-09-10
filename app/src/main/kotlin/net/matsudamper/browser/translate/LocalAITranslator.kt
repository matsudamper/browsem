package net.matsudamper.browser.translate

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator as MlKitTranslator
import com.google.mlkit.nl.translate.TranslatorOptions
import net.matsudamper.browser.resolveTranslationLanguagePair
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mozilla.geckoview.GeckoSession

class LocalAITranslator(
    private val session: GeckoSession,
    private val fromLanguage: String?,
    private val toLanguage: String,
) : Translator, KoinComponent {
    private val pageTranslationWebExtension: PageTranslationWebExtension by inject()

    override suspend fun translate(): TranslationLanguages? {
        val snapshot = try {
            pageTranslationWebExtension.scanPage(session)
        } catch (error: Exception) {
            pageTranslationWebExtension.stopTranslation(session, restoreOriginal = true)
            throw error
        }
        if (snapshot.segments.isEmpty()) {
            pageTranslationWebExtension.stopTranslation(session, restoreOriginal = false)
            return null
        }

        val sourceTranslateLanguage = resolveSourceLanguage(snapshot)
        val targetTranslateLanguage = toTranslateLanguageTag(toLanguage)
        if (sourceTranslateLanguage == null || targetTranslateLanguage == null) {
            pageTranslationWebExtension.stopTranslation(session, restoreOriginal = false)
            return null
        }
        val (effectiveSourceLanguage, effectiveTargetLanguage) = resolveTranslationLanguagePair(
            sourceTranslateLanguage,
            targetTranslateLanguage,
        )

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(effectiveSourceLanguage)
                .setTargetLanguage(effectiveTargetLanguage)
                .build(),
        )
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            val translationCache = ConcurrentHashMap<String, String>()
            translateAndApply(
                translator = translator,
                pageTranslationWebExtension = pageTranslationWebExtension,
                documentId = snapshot.documentId,
                segments = snapshot.segments,
                translationCache = translationCache,
            )
            keepTranslatingDynamicContent(
                translator = translator,
                pageTranslationWebExtension = pageTranslationWebExtension,
                documentId = snapshot.documentId,
                translationCache = translationCache,
            )
            TranslationLanguages(effectiveSourceLanguage, effectiveTargetLanguage)
        } catch (error: Exception) {
            translator.close()
            pageTranslationWebExtension.stopTranslation(session, restoreOriginal = true)
            throw error
        }
    }

    private suspend fun resolveSourceLanguage(snapshot: PageTranslationWebExtension.PageSnapshot): String? {
        if (!fromLanguage.isNullOrBlank() && fromLanguage != "und") {
            return toTranslateLanguageTag(fromLanguage)
        }
        val sample = buildLanguageDetectionSample(snapshot.segments, LANGUAGE_DETECTION_LIMIT)
        val detectedLanguage = if (sample.isBlank()) "" else detectLanguage(sample)
        val resolvedLanguage = detectedLanguage.takeUnless { it.isBlank() || it == "und" }
            ?: snapshot.htmlLanguage
            ?: TranslationPriorityLanguage.FROM
        return toTranslateLanguageTag(resolvedLanguage)
            ?: toTranslateLanguageTag(TranslationPriorityLanguage.FROM)
    }

    private suspend fun translateAndApply(
        translator: MlKitTranslator,
        pageTranslationWebExtension: PageTranslationWebExtension,
        documentId: String,
        segments: List<PageTranslationWebExtension.Segment>,
        translationCache: ConcurrentHashMap<String, String>,
    ) {
        segments.chunked(APPLY_BATCH_SIZE).forEach { batch ->
            val translations = batch.map { segment ->
                val translatedText = translationCache[segment.text] ?: translator.translate(segment.text).await().also {
                    translationCache[segment.text] = it
                }
                PageTranslationWebExtension.TranslationResult(
                    id = segment.id,
                    sourceText = segment.text,
                    translatedText = translatedText,
                )
            }
            pageTranslationWebExtension.applyTranslations(
                session = session,
                documentId = documentId,
                translations = translations,
            )
        }
    }

    private fun keepTranslatingDynamicContent(
        translator: MlKitTranslator,
        pageTranslationWebExtension: PageTranslationWebExtension,
        documentId: String,
        translationCache: ConcurrentHashMap<String, String>,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queue = Channel<List<PageTranslationWebExtension.Segment>>(Channel.UNLIMITED)
        scope.launch {
            for (segments in queue) {
                runCatching {
                    translateAndApply(
                        translator = translator,
                        pageTranslationWebExtension = pageTranslationWebExtension,
                        documentId = documentId,
                        segments = segments,
                        translationCache = translationCache,
                    )
                }
            }
        }
        pageTranslationWebExtension.activateTranslation(
            session = session,
            documentId = documentId,
            onSegments = { segments ->
                queue.trySend(segments)
            },
            onStopped = {
                queue.close()
                scope.cancel()
                translator.close()
            },
        )
    }

    private suspend fun detectLanguage(text: String): String = withContext(Dispatchers.IO) {
        val languageIdentifier = LanguageIdentification.getClient()
        languageIdentifier.use { identifier ->
            identifier.identifyLanguage(text.take(LANGUAGE_DETECTION_LIMIT)).await().orEmpty()
        }
    }

    private fun toTranslateLanguageTag(languageTag: String): String? {
        if (languageTag.isBlank() || languageTag == "und") return null
        val normalized = languageTag.lowercase(Locale.ROOT)
        return TranslateLanguage.fromLanguageTag(normalized)
            ?: TranslateLanguage.fromLanguageTag(normalized.substringBefore('-'))
    }

    companion object {
        private const val LANGUAGE_DETECTION_LIMIT = 2_000
        private const val APPLY_BATCH_SIZE = 16
    }
}

internal fun buildLanguageDetectionSample(
    segments: List<PageTranslationWebExtension.Segment>,
    maxLength: Int,
): String {
    if (maxLength <= 0) return ""
    val builder = StringBuilder()
    for (segment in segments) {
        if (builder.isNotEmpty()) builder.append('\n')
        val remaining = maxLength - builder.length
        if (remaining <= 0) break
        builder.append(segment.text.take(remaining))
        if (builder.length >= maxLength) break
    }
    return builder.toString()
}
