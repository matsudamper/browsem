package net.matsudamper.browser.translate

import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator as MlKitTranslator
import com.google.mlkit.nl.translate.TranslatorOptions
import net.matsudamper.browser.data.crashlog.CrashLogRepository
import net.matsudamper.browser.resolveTranslationLanguagePair
import org.mozilla.geckoview.GeckoSession

class LocalAITranslator(
    private val session: GeckoSession,
    private val currentPageUrl: String,
    private val fromLanguage: String?,
    private val toLanguage: String,
    private val pageTranslationWebExtension: PageTranslationWebExtension,
    private val crashLogRepository: CrashLogRepository,
    private val onTranslateStateChanged: (Translator.TranslateState) -> Unit,
    private val onTranslateProgressChanged: (TranslationProgress) -> Unit,
) : Translator {
    private val translatedSegmentCount = AtomicInteger(0)
    private val totalSegmentCount = AtomicInteger(0)

    override suspend fun translate(): TranslationLanguages? {
        onTranslateStateChanged(Translator.TranslateState.PAGE_SCAN)
        val snapshot = try {
            pageTranslationWebExtension.scanPage(session, currentPageUrl)
        } catch (error: Exception) {
            pageTranslationWebExtension.stopTranslation(session, restoreOriginal = true)
            throw error
        }
        if (snapshot.segments.isEmpty()) {
            // 何も翻訳していないのに翻訳済みと表示されると、失敗に気付けない
            pageTranslationWebExtension.stopTranslation(session, restoreOriginal = false)
            throw IllegalStateException("ページから翻訳対象のテキストを取得できませんでした")
        }

        onTranslateStateChanged(Translator.TranslateState.LANGUAGE_DETECTION)
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
            onTranslateStateChanged(Translator.TranslateState.MODEL_DOWNLOAD)
            prepareTranslationModel(
                translator = translator,
                sourceLanguage = effectiveSourceLanguage,
                targetLanguage = effectiveTargetLanguage,
                segmentCount = snapshot.segments.size,
            )
            val translationCache = ConcurrentHashMap<String, String>()
            totalSegmentCount.set(snapshot.segments.size)
            notifyProgress()
            val initialSegments = snapshot.segments.take(INITIAL_APPLY_SEGMENT_COUNT)
            val remainingSegments = snapshot.segments.drop(INITIAL_APPLY_SEGMENT_COUNT)
            onTranslateStateChanged(Translator.TranslateState.TRANSLATING)
            translateInitialSegments(
                translator = translator,
                pageTranslationWebExtension = pageTranslationWebExtension,
                documentId = snapshot.documentId,
                segments = initialSegments,
                translationCache = translationCache,
                sourceLanguage = effectiveSourceLanguage,
                targetLanguage = effectiveTargetLanguage,
            )
            val activated = keepTranslatingDynamicContent(
                translator = translator,
                pageTranslationWebExtension = pageTranslationWebExtension,
                documentId = snapshot.documentId,
                initialSegments = remainingSegments,
                translationCache = translationCache,
            )
            if (!activated) {
                throw IllegalStateException("ページ翻訳ブリッジを継続翻訳状態へ移行できませんでした")
            }
            TranslationLanguages(effectiveSourceLanguage, effectiveTargetLanguage)
        } catch (error: Exception) {
            translator.close()
            pageTranslationWebExtension.stopTranslation(session, restoreOriginal = true)
            throw error
        }
    }

    private suspend fun prepareTranslationModel(
        translator: MlKitTranslator,
        sourceLanguage: String,
        targetLanguage: String,
        segmentCount: Int,
    ) {
        // 初回ダウンロードは秒単位かかるのが正常で、遅延として残すと言語ペアごとに必ずログが出る。
        // モデルが揃っているのに遅いときだけ診断の価値があるため、事前の状態を見て切り分ける。
        val isModelDownloadedBeforePreparation = isTranslationModelDownloaded(sourceLanguage, targetLanguage)
        val startedAt = SystemClock.elapsedRealtime()
        try {
            withTimeout(MODEL_PREPARATION_TIMEOUT_MS) {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            }
        } catch (error: TimeoutCancellationException) {
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            saveInfo(
                title = "ローカルAI翻訳モデル準備タイムアウト",
                body = buildTranslationDiagnostics(
                    stage = "model",
                    elapsedMs = elapsedMs,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    segmentCount = segmentCount,
                ),
            )
            throw IllegalStateException(
                "ローカルAI翻訳モデルの準備が${MODEL_PREPARATION_TIMEOUT_MS}ms以内に完了しませんでした",
                error,
            )
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (isModelDownloadedBeforePreparation && elapsedMs >= SLOW_MODEL_PREPARATION_THRESHOLD_MS) {
            saveInfo(
                title = "ローカルAI翻訳モデル準備遅延",
                body = buildTranslationDiagnostics(
                    stage = "model",
                    elapsedMs = elapsedMs,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    segmentCount = segmentCount,
                ),
            )
        }
    }

    /** 翻訳に必要なモデルが両方ダウンロード済みかを返す。判定できない場合は未ダウンロード扱いにする */
    private suspend fun isTranslationModelDownloaded(sourceLanguage: String, targetLanguage: String): Boolean {
        val remoteModelManager = RemoteModelManager.getInstance()
        return try {
            listOf(sourceLanguage, targetLanguage).all { language ->
                remoteModelManager.isModelDownloaded(TranslateRemoteModel.Builder(language).build()).await()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "翻訳モデルのダウンロード状態を取得できませんでした", error)
            false
        }
    }

    private suspend fun translateInitialSegments(
        translator: MlKitTranslator,
        pageTranslationWebExtension: PageTranslationWebExtension,
        documentId: String,
        segments: List<PageTranslationWebExtension.Segment>,
        translationCache: ConcurrentHashMap<String, String>,
        sourceLanguage: String,
        targetLanguage: String,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val applyResult = withTimeout(INITIAL_TRANSLATION_TIMEOUT_MS) {
                translateAndApply(
                    translator = translator,
                    pageTranslationWebExtension = pageTranslationWebExtension,
                    documentId = documentId,
                    segments = segments,
                    translationCache = translationCache,
                    awaitDomApply = true,
                )
            }
            verifyInitialApply(applyResult)
        } catch (error: TimeoutCancellationException) {
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            saveInfo(
                title = "ローカルAI初期翻訳タイムアウト",
                body = buildTranslationDiagnostics(
                    stage = "initial",
                    elapsedMs = elapsedMs,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    segmentCount = segments.size,
                ),
            )
            throw IllegalStateException(
                "ローカルAIの初期翻訳が${INITIAL_TRANSLATION_TIMEOUT_MS}ms以内に完了しませんでした",
                error,
            )
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (elapsedMs >= SLOW_INITIAL_TRANSLATION_THRESHOLD_MS) {
            saveInfo(
                title = "ローカルAI初期翻訳遅延",
                body = buildTranslationDiagnostics(
                    stage = "initial",
                    elapsedMs = elapsedMs,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    segmentCount = segments.size,
                ),
            )
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
        awaitDomApply: Boolean = false,
    ): PageTranslationWebExtension.ApplyResult {
        var documentMatched = true
        var appliedCount = 0
        var requeuedCount = 0
        segments.chunked(APPLY_BATCH_SIZE).forEach { batch ->
            val translations = batch.map { segment ->
                val translatedText = translationCache[segment.text] ?: translator.translate(segment.text).await().also {
                    translationCache[segment.text] = it
                }
                translatedSegmentCount.incrementAndGet()
                notifyProgress()
                PageTranslationWebExtension.TranslationResult(
                    id = segment.id,
                    sourceText = segment.text,
                    translatedText = translatedText,
                )
            }
            if (awaitDomApply) {
                val batchResult = pageTranslationWebExtension.applyTranslationsAndAwait(
                    session = session,
                    documentId = documentId,
                    translations = translations,
                )
                documentMatched = documentMatched && batchResult.documentMatched
                appliedCount += batchResult.appliedCount
                requeuedCount += batchResult.requeuedCount
            } else {
                pageTranslationWebExtension.applyTranslations(
                    session = session,
                    documentId = documentId,
                    translations = translations,
                )
            }
        }
        return PageTranslationWebExtension.ApplyResult(
            documentMatched = documentMatched,
            appliedCount = appliedCount,
            requeuedCount = requeuedCount,
        )
    }

    /**
     * 反映件数が0でも、ページ側が書き換えたノードは継続翻訳で訳し直されるため失敗にしない。
     */
    private fun verifyInitialApply(applyResult: PageTranslationWebExtension.ApplyResult) {
        if (!applyResult.documentMatched) {
            throw IllegalStateException("翻訳結果の反映先が別のページに切り替わりました")
        }
        if (applyResult.appliedCount == 0 && applyResult.requeuedCount == 0) {
            throw IllegalStateException("ローカルAIの翻訳結果をページへ反映できませんでした")
        }
    }

    private fun keepTranslatingDynamicContent(
        translator: MlKitTranslator,
        pageTranslationWebExtension: PageTranslationWebExtension,
        documentId: String,
        initialSegments: List<PageTranslationWebExtension.Segment>,
        translationCache: ConcurrentHashMap<String, String>,
    ): Boolean {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queue = Channel<List<PageTranslationWebExtension.Segment>>(Channel.UNLIMITED)
        scope.launch {
            for (segments in queue) {
                try {
                    translateAndApply(
                        translator = translator,
                        pageTranslationWebExtension = pageTranslationWebExtension,
                        documentId = documentId,
                        segments = segments,
                        translationCache = translationCache,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    saveInfo(
                        title = "ローカルAI継続翻訳失敗",
                        body = "stage=background\nsegmentCount=${segments.size}\nerror=${error.javaClass.name}\nmessage=${error.message.orEmpty()}",
                    )
                }
            }
        }
        val activated = pageTranslationWebExtension.activateTranslation(
            session = session,
            documentId = documentId,
            onSegments = { segments ->
                totalSegmentCount.addAndGet(segments.size)
                notifyProgress()
                queue.trySend(segments)
            },
            onStopped = {
                queue.close()
                scope.cancel()
                translator.close()
            },
        )
        if (!activated) return false
        if (initialSegments.isNotEmpty()) {
            queue.trySend(initialSegments)
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

    private fun toTranslateLanguageTag(languageTag: String): String? {
        if (languageTag.isBlank() || languageTag == "und") return null
        val normalized = languageTag.lowercase(Locale.ROOT)
        return TranslateLanguage.fromLanguageTag(normalized)
            ?: TranslateLanguage.fromLanguageTag(normalized.substringBefore('-'))
    }

    private fun buildTranslationDiagnostics(
        stage: String,
        elapsedMs: Long,
        sourceLanguage: String,
        targetLanguage: String,
        segmentCount: Int,
    ): String = buildString {
        appendLine("stage=$stage")
        appendLine("elapsedMs=$elapsedMs")
        appendLine("sourceLanguage=$sourceLanguage")
        appendLine("targetLanguage=$targetLanguage")
        append("segmentCount=$segmentCount")
    }

    private fun saveInfo(title: String, body: String) {
        try {
            crashLogRepository.saveInfoSync(title, body)
        } catch (error: RuntimeException) {
            Log.w(TAG, "翻訳診断ログの保存に失敗", error)
        }
    }

    companion object {
        private const val TAG = "LocalAITranslator"
        private const val LANGUAGE_DETECTION_LIMIT = 2_000
        private const val APPLY_BATCH_SIZE = 16
        private const val INITIAL_APPLY_SEGMENT_COUNT = 8
        private const val MODEL_PREPARATION_TIMEOUT_MS = 30_000L
        private const val INITIAL_TRANSLATION_TIMEOUT_MS = 15_000L
        private const val SLOW_MODEL_PREPARATION_THRESHOLD_MS = 5_000L
        private const val SLOW_INITIAL_TRANSLATION_THRESHOLD_MS = 5_000L
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
