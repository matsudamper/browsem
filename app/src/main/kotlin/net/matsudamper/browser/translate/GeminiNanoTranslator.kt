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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.nl.languageid.LanguageIdentification
import net.matsudamper.browser.data.crashlog.CrashLogRepository
import net.matsudamper.browser.resolveTranslationLanguagePair
import org.mozilla.geckoview.GeckoSession

class GeminiNanoTranslator(
    private val session: GeckoSession,
    private val currentPageUrl: String,
    private val fromLanguage: String?,
    private val toLanguage: String,
    private val pageTranslationWebExtension: PageTranslationWebExtension,
    private val crashLogRepository: CrashLogRepository,
    private val onTranslateStateChanged: (Translator.TranslateState) -> Unit,
    private val onTranslateProgressChanged: (TranslationProgress) -> Unit,
) : Translator {
    private var currentStage: String = STAGE_SCAN
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
            val inference = GeminiNanoInference(
                generativeModel = Generation.getClient(),
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
            )
            try {
                currentStage = STAGE_MODEL
                onTranslateStateChanged(Translator.TranslateState.MODEL_DOWNLOAD)
                inference.prepare()

                currentStage = STAGE_INITIAL
                onTranslateStateChanged(Translator.TranslateState.TRANSLATING)
                val translationCache = ConcurrentHashMap<String, String>()
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
        translationCache: ConcurrentHashMap<String, String>,
        awaitDomApply: Boolean,
    ): PageTranslationWebExtension.ApplyResult {
        var documentMatched = true
        var appliedCount = 0
        var requeuedCount = 0
        for (batch in segments.chunked(APPLY_BATCH_SIZE)) {
            val translations = batch.map { segment ->
                val translatedText = translationCache[segment.text]
                    ?: inference.translateTextOrKeepSource(segment.text).also { translated ->
                        translationCache[segment.text] = translated
                    }
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
        translationCache: ConcurrentHashMap<String, String>,
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
        appendLine("elapsedMs=$elapsedMs")
        appendLine("fromLanguage=${fromLanguage.orEmpty()}")
        appendLine("toLanguage=$toLanguage")
        appendLine("error=${error.javaClass.name}")
        appendLine("message=${error.message.orEmpty()}")
        append("cause=${error.cause?.javaClass?.name.orEmpty()}")
    }

    private fun saveInfo(title: String, body: String) {
        try {
            crashLogRepository.saveInfoSync(title, body)
        } catch (error: RuntimeException) {
            Log.w(TAG, "翻訳診断ログの保存に失敗", error)
        }
    }

    companion object {
        private const val TAG = "GeminiNanoTranslator"
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

/**
 * Gemini Nano への推論要求をまとめる。
 *
 * 同一モデルへ並行して生成要求を出すと失敗するため、[inferenceMutex] で直列化する。
 */
private class GeminiNanoInference(
    private val generativeModel: GenerativeModel,
    private val sourceLanguage: String,
    private val targetLanguage: String,
) {
    private val inferenceMutex = Mutex()

    @Volatile
    private var systemPromptAvailable = false

    suspend fun prepare() {
        withTimeout(MODEL_PREPARATION_TIMEOUT_MS) {
            while (true) {
                when (generativeModel.checkStatus()) {
                    FeatureStatus.AVAILABLE -> {
                        generativeModel.warmup()
                        systemPromptAvailable = runCatching { generativeModel.isSystemPromptAvailable() }
                            .getOrDefault(false)
                        return@withTimeout
                    }

                    FeatureStatus.DOWNLOADABLE -> {
                        generativeModel.download().collect { status ->
                            if (status is DownloadStatus.DownloadFailed) {
                                throw status.e
                            }
                        }
                    }

                    FeatureStatus.DOWNLOADING -> delay(MODEL_STATUS_POLL_INTERVAL_MS)

                    FeatureStatus.UNAVAILABLE -> {
                        throw IllegalStateException("Gemini Nanoを利用できない端末です")
                    }

                    else -> throw IllegalStateException("Gemini Nanoの利用状態を判定できませんでした")
                }
            }
        }
    }

    suspend fun translateText(text: String): String {
        val chunks = splitToChunks(text)
        val translatedChunks = chunks.map { chunk -> translateChunk(chunk) }
        return joinTranslatedChunks(chunks, translatedChunks)
    }

    /** 原文のままにしたい場合に、翻訳失敗を呼び出し元へ伝えずに済ませる */
    suspend fun translateTextOrKeepSource(text: String): String {
        return try {
            translateText(text)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Gemini Nanoの翻訳に失敗したため原文を維持する", error)
            text
        }
    }

    fun close() {
        generativeModel.close()
    }

    private suspend fun translateChunk(chunk: String): String {
        var lastError: Exception? = null
        repeat(GENERATION_ATTEMPT_COUNT) { attempt ->
            try {
                // 出力上限で途切れた場合に備え、再試行では上限いっぱいを割り当てる
                val maxOutputTokens = if (attempt == 0) estimateMaxOutputTokens(chunk) else MAX_OUTPUT_TOKENS
                return generateTranslation(chunk, maxOutputTokens)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                Log.w(TAG, "Gemini Nanoの生成に失敗したため再試行する: attempt=${attempt + 1}", error)
                delay(GENERATION_RETRY_INTERVAL_MS)
            }
        }
        throw IllegalStateException("Gemini Nanoの翻訳生成に失敗しました", lastError)
    }

    private suspend fun generateTranslation(chunk: String, maxOutputTokenCount: Int): String {
        val instruction = buildGeminiNanoTranslationInstruction(sourceLanguage, targetLanguage)
        val configure: GenerateContentRequest.Builder.() -> Unit = {
            temperature = 0f
            topK = 1
            candidateCount = 1
            maxOutputTokens = maxOutputTokenCount
        }
        // 指示と原文を同じ入力に混ぜると、指示文そのものを訳して返すことがある
        val request = if (systemPromptAvailable) {
            generateContentRequest(SystemInstruction(instruction), TextPart(chunk), configure)
        } else {
            generateContentRequest(
                TextPart(buildGeminiNanoTranslationPrompt(sourceLanguage, targetLanguage, chunk)),
                configure,
            )
        }
        val response = inferenceMutex.withLock {
            withTimeout(GENERATION_TIMEOUT_MS) {
                generativeModel.generateContent(request)
            }
        }
        // finishReason は端末実装によっては null になるため、STOP 以外を一律失敗にはしない。
        // ただし MAX_TOKENS は訳文が途中で切れているため採用しない
        val candidate = response.candidates
            .firstOrNull { sanitizeTranslatedText(it.text).isNotBlank() }
            ?: throw IllegalStateException("Gemini Nanoから翻訳結果を取得できませんでした")
        if (candidate.finishReason == Candidate.FinishReason.MAX_TOKENS) {
            throw IllegalStateException("Gemini Nanoの翻訳が出力上限で途切れました")
        }
        val translatedText = sanitizeTranslatedText(candidate.text)
        if (isUnusableTranslation(translatedText)) {
            throw IllegalStateException("Gemini Nanoが訳文ではない返答をしました")
        }
        return translatedText
    }

    /** 翻訳文は原文よりトークン数が増えるため、文字数から余裕を持った上限を見積もる */
    private fun estimateMaxOutputTokens(chunk: String): Int =
        (chunk.length * OUTPUT_TOKENS_PER_SOURCE_CHAR + OUTPUT_TOKENS_MARGIN)
            .coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS)

    private fun splitToChunks(text: String): List<String> {
        if (text.length <= CHUNK_CHAR_LIMIT) return listOf(text)
        val chunks = mutableListOf<String>()
        var rest = text
        while (rest.length > CHUNK_CHAR_LIMIT) {
            val splitIndex = findChunkSplitIndex(rest)
            chunks.add(rest.substring(0, splitIndex))
            rest = rest.substring(splitIndex)
        }
        if (rest.isNotEmpty()) {
            chunks.add(rest)
        }
        return chunks
    }

    /** 文末・空白を優先しつつ、[CHUNK_CHAR_LIMIT] を超えない位置で区切る */
    private fun findChunkSplitIndex(text: String): Int {
        val limit = CHUNK_CHAR_LIMIT.coerceAtMost(text.length)
        val sentenceEnd = (limit - 1 downTo CHUNK_MIN_SPLIT_INDEX)
            .firstOrNull { index -> text[index] in SENTENCE_END_CHARS }
        if (sentenceEnd != null) return sentenceEnd + 1
        val whitespace = (limit - 1 downTo CHUNK_MIN_SPLIT_INDEX)
            .firstOrNull { index -> text[index].isWhitespace() }
        if (whitespace != null) return whitespace + 1
        return if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
    }

    private fun joinTranslatedChunks(
        sourceChunks: List<String>,
        translatedChunks: List<String>,
    ): String = buildString {
        translatedChunks.forEachIndexed { index, translatedChunk ->
            if (index > 0) {
                val previousSourceChunk = sourceChunks[index - 1]
                val sourceChunk = sourceChunks[index]
                when {
                    previousSourceChunk.endsWith('\n') || sourceChunk.startsWith('\n') -> append('\n')

                    previousSourceChunk.lastOrNull()?.isWhitespace() == true ||
                        sourceChunk.firstOrNull()?.isWhitespace() == true -> append(' ')
                }
            }
            append(translatedChunk)
        }
    }

    companion object {
        private const val TAG = "GeminiNanoInference"
        private const val CHUNK_CHAR_LIMIT = 400
        private const val CHUNK_MIN_SPLIT_INDEX = 1
        private val SENTENCE_END_CHARS = charArrayOf('.', '!', '?', '。', '！', '？', '\n')
        private const val OUTPUT_TOKENS_PER_SOURCE_CHAR = 2
        private const val OUTPUT_TOKENS_MARGIN = 64
        private const val MIN_OUTPUT_TOKENS = 128
        private const val MAX_OUTPUT_TOKENS = 1_024
        private const val GENERATION_ATTEMPT_COUNT = 2
        private const val GENERATION_RETRY_INTERVAL_MS = 200L
        private const val GENERATION_TIMEOUT_MS = 60_000L
        private const val MODEL_PREPARATION_TIMEOUT_MS = 180_000L
        private const val MODEL_STATUS_POLL_INTERVAL_MS = 500L
    }
}

internal suspend fun isGeminiNanoAvailable(): Boolean {
    val generativeModel = try {
        Generation.getClient()
    } catch (error: Exception) {
        return false
    }
    return try {
        when (generativeModel.checkStatus()) {
            FeatureStatus.AVAILABLE,
            FeatureStatus.DOWNLOADABLE,
            FeatureStatus.DOWNLOADING,
            -> true

            else -> false
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        false
    } finally {
        generativeModel.close()
    }
}

/** 数字や記号だけのテキストは翻訳しても変化がないため、推論対象から除外する */
internal fun isTranslatableText(text: String): Boolean = text.any { it.isLetter() }

/**
 * 小型モデルでも指示が崩れないよう、言語名を明示した短いプロンプトにする。
 */
internal fun buildGeminiNanoTranslationInstruction(
    sourceLanguage: String,
    targetLanguage: String,
): String {
    val sourceName = toLanguageDisplayName(sourceLanguage)
    val targetName = toLanguageDisplayName(targetLanguage)
    return "Translate the user text from $sourceName to $targetName. " +
        "Output only the $targetName translation of that text. " +
        "If you cannot translate it, output the text unchanged and never explain why."
}

internal fun buildGeminiNanoTranslationPrompt(
    sourceLanguage: String,
    targetLanguage: String,
    text: String,
): String {
    val targetName = toLanguageDisplayName(targetLanguage)
    return buildString {
        appendLine(buildGeminiNanoTranslationInstruction(sourceLanguage, targetLanguage))
        appendLine()
        appendLine(text)
        appendLine()
        append("$targetName:")
    }
}

/**
 * 訳文ではなく指示文の翻訳や翻訳拒否の返答を、ページへ差し込まないための判定。
 *
 * 小型モデルはこの手の返答を避けきれないため、訳文として採用せず原文を残す。
 */
internal fun isUnusableTranslation(text: String): Boolean {
    val normalized = text.replace(" ", "").replace("　", "")
    return UNUSABLE_TRANSLATION_MARKERS.any { marker -> normalized.contains(marker) }
}

private val UNUSABLE_TRANSLATION_MARKERS = listOf(
    "翻訳のみ",
    "訳のみ",
    "指示ではありません",
    "指示ではなく",
    "翻訳してください",
    "翻訳できません",
    "翻訳できない",
    "翻訳することはできません",
    "申し訳ありませんが",
    "申し訳ございませんが",
    "AIアシスタント",
    "translationonly",
    "Outputonlythe",
    "Translatetheusertext",
    "cannottranslate",
    "can'ttranslate",
    "unabletotranslate",
    "I'msorry",
    "Iamsorry",
    "Asanailanguagemodel",
    "Asanaimodel",
)

private fun toLanguageDisplayName(languageTag: String): String =
    Locale.forLanguageTag(languageTag)
        .getDisplayLanguage(Locale.ENGLISH)
        .ifBlank { languageTag }

/** モデルが付けがちなラベル・引用符・コードフェンスを取り除く */
internal fun sanitizeTranslatedText(text: String): String {
    var sanitized = text.trim()
    sanitized = sanitized.removeSurrounding("```").trim()
    sanitized = TRANSLATION_LABEL_REGEX.replace(sanitized, "").trim()
    QUOTE_PAIRS.forEach { (open, close) ->
        if (sanitized.length >= 2 && sanitized.first() == open && sanitized.last() == close) {
            sanitized = sanitized.substring(1, sanitized.length - 1).trim()
        }
    }
    return sanitized
}

private val TRANSLATION_LABEL_REGEX = Regex(
    "^(translation|translated text|output|翻訳|訳)\\s*[:：]\\s*",
    RegexOption.IGNORE_CASE,
)

private val QUOTE_PAIRS = listOf(
    '"' to '"',
    '\'' to '\'',
    '「' to '」',
    '“' to '”',
)
