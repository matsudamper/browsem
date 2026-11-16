package net.matsudamper.browser.translate

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.google.mlkit.nl.languageid.LanguageIdentification
import net.matsudamper.browser.data.crashlog.CrashLogRepository
import net.matsudamper.browser.resolveTranslationLanguagePair
import org.mozilla.geckoview.GeckoSession

class GeminiNanoTranslator(
    private val session: GeckoSession,
    private val currentPageUrl: String,
    private val fromLanguage: String?,
    private val toLanguage: String,
    private val modelKey: String,
    private val pageTranslationWebExtension: PageTranslationWebExtension,
    private val pageTranslationCache: PageTranslationCache,
    private val crashLogRepository: CrashLogRepository,
    private val onTranslateStateChanged: (Translator.TranslateState) -> Unit,
    private val onTranslateProgressChanged: (TranslationProgress) -> Unit,
) : Translator {
    private var currentStage: String = STAGE_SCAN
    private var currentModelKey: String = ""
    private val translatedSegmentCount = AtomicInteger(0)
    private val totalSegmentCount = AtomicInteger(0)

    override suspend fun translate(): TranslationLanguages? {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            translateInternal()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            saveInfo(
                title = "Gemini Nano翻訳失敗",
                body = buildDiagnostics(
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    error = error,
                ),
            )
            throw error
        }
    }

    private suspend fun translateInternal(): TranslationLanguages? {
        currentStage = STAGE_SCAN
        onTranslateStateChanged(Translator.TranslateState.PAGE_SCAN)
        val snapshot = try {
            pageTranslationWebExtension.scanPage(session, currentPageUrl)
        } catch (error: Exception) {
            pageTranslationWebExtension.stopTranslation(session, restoreOriginal = true)
            throw error
        }
        val translatableSegments = snapshot.segments.filter { isTranslatableText(it.text) }
        if (translatableSegments.isEmpty()) {
            // 何も翻訳していないのに翻訳済みと表示されると、失敗に気付けない
            pageTranslationWebExtension.stopTranslation(session, restoreOriginal = false)
            throw IllegalStateException("ページから翻訳対象のテキストを取得できませんでした")
        }

        return try {
            currentStage = STAGE_LANGUAGE
            onTranslateStateChanged(Translator.TranslateState.LANGUAGE_DETECTION)
            val (sourceLanguage, targetLanguage) = resolveTranslationLanguagePair(
                resolveSourceLanguage(snapshot),
                toLanguage,
            )
            val selectedModel = GeminiNanoModel.select(modelKey)
                ?: throw IllegalStateException("Gemini Nanoを利用できない端末です")
            currentModelKey = selectedModel.key
            val inference = GeminiNanoInference(
                generativeModel = selectedModel.generativeModel,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
            )
            try {
                currentStage = STAGE_MODEL
                onTranslateStateChanged(Translator.TranslateState.MODEL_DOWNLOAD)
                inference.prepare()

                currentStage = STAGE_INITIAL
                onTranslateStateChanged(Translator.TranslateState.TRANSLATING)
                val translationCache = pageTranslationCache.forTranslator(
                    translatorKey = "$CACHE_TRANSLATOR_KEY_PREFIX$currentModelKey",
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                )
                totalSegmentCount.set(translatableSegments.size)
                notifyProgress()
                val initialSegments = translatableSegments.take(INITIAL_APPLY_SEGMENT_COUNT)
                val remainingSegments = translatableSegments.drop(INITIAL_APPLY_SEGMENT_COUNT)
                val applyResult = try {
                    withTimeout(INITIAL_TRANSLATION_TIMEOUT_MS) {
                        translateAndApply(
                            inference = inference,
                            documentId = snapshot.documentId,
                            segments = initialSegments,
                            translationCache = translationCache,
                            awaitDomApply = true,
                        )
                    }
                } catch (error: TimeoutCancellationException) {
                    throw IllegalStateException(
                        "Gemini Nanoの初期翻訳が${INITIAL_TRANSLATION_TIMEOUT_MS}ms以内に完了しませんでした",
                        error,
                    )
                }
                verifyInitialApply(applyResult)

                currentStage = STAGE_ACTIVATE
                val activated = keepTranslatingDynamicContent(
                    inference = inference,
                    documentId = snapshot.documentId,
                    remainingSegments = remainingSegments,
                    translationCache = translationCache,
                )
                if (!activated) {
                    throw IllegalStateException("ページ翻訳ブリッジを継続翻訳状態へ移行できませんでした")
                }
                TranslationLanguages(sourceLanguage, targetLanguage)
            } catch (error: Exception) {
                inference.close()
                throw error
            }
        } catch (error: Exception) {
            pageTranslationWebExtension.stopTranslation(session, restoreOriginal = true)
            throw error
        }
    }

    private suspend fun resolveSourceLanguage(snapshot: PageTranslationWebExtension.PageSnapshot): String {
        if (!fromLanguage.isNullOrBlank() && fromLanguage != UNDETERMINED_LANGUAGE) {
            return fromLanguage
        }
        val sample = buildLanguageDetectionSample(snapshot.segments, LANGUAGE_DETECTION_LIMIT)
        val detectedLanguage = if (sample.isBlank()) "" else detectLanguage(sample)
        return detectedLanguage.takeUnless { it.isBlank() || it == UNDETERMINED_LANGUAGE }
            ?: snapshot.htmlLanguage
            ?: TranslationPriorityLanguage.FROM
    }

    private suspend fun translateAndApply(
        inference: GeminiNanoInference,
        documentId: String,
        segments: List<PageTranslationWebExtension.Segment>,
        translationCache: PageTranslationCache.LanguagePairCache,
        awaitDomApply: Boolean,
    ): PageTranslationWebExtension.ApplyResult {
        var documentMatched = true
        var appliedCount = 0
        var requeuedCount = 0
        for (batch in segments.chunked(APPLY_BATCH_SIZE)) {
            val translations = batch.map { segment ->
                val translatedText = translationCache[segment.text]
                    ?: translateAndCache(inference, translationCache, segment.text)
                translatedSegmentCount.incrementAndGet()
                notifyProgress()
                PageTranslationWebExtension.TranslationResult(
                    id = segment.id,
                    sourceText = segment.text,
                    translatedText = translatedText,
                )
            }
            if (!awaitDomApply) {
                pageTranslationWebExtension.applyTranslations(
                    session = session,
                    documentId = documentId,
                    translations = translations,
                )
                continue
            }
            val batchResult = pageTranslationWebExtension.applyTranslationsAndAwait(
                session = session,
                documentId = documentId,
                translations = translations,
            )
            appliedCount += batchResult.appliedCount
            requeuedCount += batchResult.requeuedCount
            if (!batchResult.documentMatched) {
                // 反映先が別ページへ変わった後に推論を続けても無駄になる
                documentMatched = false
                break
            }
        }
        return PageTranslationWebExtension.ApplyResult(
            documentMatched = documentMatched,
            appliedCount = appliedCount,
            requeuedCount = requeuedCount,
        )
    }

    /** 失敗時は原文を表示するが、再翻訳で推論をやり直せるようキャッシュには残さない */
    private suspend fun translateAndCache(
        inference: GeminiNanoInference,
        translationCache: PageTranslationCache.LanguagePairCache,
        text: String,
    ): String {
        val translatedText = inference.translateTextOrNull(text) ?: return text
        translationCache[text] = translatedText
        return translatedText
    }

    /**
     * 反映件数が0でも、ページ側が書き換えたノードは継続翻訳で訳し直されるため失敗にしない。
     */
    private fun verifyInitialApply(applyResult: PageTranslationWebExtension.ApplyResult) {
        if (!applyResult.documentMatched) {
            throw IllegalStateException("翻訳結果の反映先が別のページに切り替わりました")
        }
        if (applyResult.appliedCount == 0 && applyResult.requeuedCount == 0) {
            throw IllegalStateException("Gemini Nanoの翻訳結果をページへ反映できませんでした")
        }
        if (applyResult.appliedCount == 0) {
            saveInfo(
                title = "Gemini Nano初期反映0件",
                body = "requeuedCount=${applyResult.requeuedCount}",
            )
        }
    }

    private fun keepTranslatingDynamicContent(
        inference: GeminiNanoInference,
        documentId: String,
        remainingSegments: List<PageTranslationWebExtension.Segment>,
        translationCache: PageTranslationCache.LanguagePairCache,
    ): Boolean {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queue = Channel<List<PageTranslationWebExtension.Segment>>(
            capacity = DYNAMIC_TRANSLATION_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        scope.launch {
            for (segments in queue) {
                val targets = segments.filter { isTranslatableText(it.text) }
                if (targets.isEmpty()) continue
                try {
                    translateAndApply(
                        inference = inference,
                        documentId = documentId,
                        segments = targets,
                        translationCache = translationCache,
                        awaitDomApply = false,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Gemini Nanoの継続翻訳に失敗", error)
                    saveInfo(
                        title = "Gemini Nano継続翻訳失敗",
                        body = "stage=$STAGE_DYNAMIC\nsegmentCount=${targets.size}\n" +
                            "error=${error.javaClass.name}\nmessage=${error.message.orEmpty()}",
                    )
                }
            }
        }
        val activated = pageTranslationWebExtension.activateTranslation(
            session = session,
            documentId = documentId,
            onSegments = { segments ->
                totalSegmentCount.addAndGet(segments.count { isTranslatableText(it.text) })
                notifyProgress()
                queue.trySend(segments)
            },
            onStopped = {
                queue.close()
                scope.cancel()
                inference.close()
            },
        )
        if (!activated) return false
        if (remainingSegments.isNotEmpty()) {
            queue.trySend(remainingSegments)
        }
        return true
    }

    private fun notifyProgress() {
        onTranslateProgressChanged(
            TranslationProgress(
                translatedCount = translatedSegmentCount.get(),
                totalCount = totalSegmentCount.get(),
            ),
        )
    }

    private suspend fun detectLanguage(text: String): String = withContext(Dispatchers.IO) {
        val languageIdentifier = LanguageIdentification.getClient()
        languageIdentifier.use { identifier ->
            identifier.identifyLanguage(text.take(LANGUAGE_DETECTION_LIMIT)).await().orEmpty()
        }
    }

    private fun buildDiagnostics(elapsedMs: Long, error: Throwable): String = buildString {
        appendLine("stage=$currentStage")
        appendLine("model=$currentModelKey")
        appendLine("elapsedMs=$elapsedMs")
        appendLine("fromLanguage=${fromLanguage.orEmpty()}")
        appendLine("toLanguage=$toLanguage")
        appendLine("error=${error.javaClass.name}")
        appendLine("message=${error.message.orEmpty()}")
        append("cause=${error.cause?.javaClass?.name.orEmpty()}")
    }

    private fun saveInfo(title: String, body: String) {
        try {
            crashLogRepository.saveSync(tag = TAG, title = title, body = body)
        } catch (error: RuntimeException) {
            Log.w(TAG, "翻訳診断ログの保存に失敗", error)
        }
    }

    companion object {
        private const val TAG = "GeminiNanoTranslator"

        /** 実際に選ばれたモデルを含め、モデルを切り替えたら別のキャッシュになるようにする */
        private const val CACHE_TRANSLATOR_KEY_PREFIX = "gemini-nano/"
        private const val UNDETERMINED_LANGUAGE = "und"
        private const val LANGUAGE_DETECTION_LIMIT = 2_000

        /** 生成1件ごとに反映し、推論中のDOM書き換えで結果が捨てられる時間を短くする */
        private const val APPLY_BATCH_SIZE = 1

        /** DOM更新が推論速度を上回っても未処理セグメントを溜め込まないようにする */
        private const val DYNAMIC_TRANSLATION_QUEUE_CAPACITY = 16

        /** 画面に見える範囲が訳される前に翻訳済みと表示されないよう、初回でまとめて反映する */
        private const val INITIAL_APPLY_SEGMENT_COUNT = 8

        /** 長いテキストノードでも継続翻訳へ移行できるよう、初期翻訳全体に期限を設ける */
        private const val INITIAL_TRANSLATION_TIMEOUT_MS = 180_000L
        private const val STAGE_SCAN = "scan"
        private const val STAGE_LANGUAGE = "language"
        private const val STAGE_MODEL = "model"
        private const val STAGE_INITIAL = "initial"
        private const val STAGE_ACTIVATE = "activate"
        private const val STAGE_DYNAMIC = "dynamic"
    }
}

/** 数字や記号だけのテキストは翻訳しても変化がないため、推論対象から除外する */
internal fun isTranslatableText(text: String): Boolean = text.any { it.isLetter() }
