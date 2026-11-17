package net.matsudamper.browser.translate

import android.util.Log
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest

/**
 * Gemini Nano への推論要求をまとめる。
 *
 * 同一モデルへ並行して生成要求を出すと失敗するため、[inferenceMutex] で直列化する。
 */
internal class GeminiNanoInference(
    private val generativeModel: GenerativeModel,
    private val sourceLanguage: String,
    private val targetLanguage: String,
    private val refusalLikeOutput: RefusalLikeOutput,
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

    /** 翻訳失敗を呼び出し元へ伝えず、原文のまま表示する判断を任せる */
    suspend fun translateTextOrNull(text: String): String? {
        return try {
            translateText(text)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Gemini Nanoの翻訳に失敗したため原文を維持する", error)
            null
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
            // 思考は1セグメントごとの待ち時間を伸ばすうえ、思考文が訳文に混ざることがある
            enableThinking = false
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
        if (refusalLikeOutput == RefusalLikeOutput.REJECT && isUnusableTranslation(translatedText)) {
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
        return buildList {
            var rest = text
            while (rest.length > CHUNK_CHAR_LIMIT) {
                val splitIndex = findChunkSplitIndex(rest)
                add(rest.substring(0, splitIndex))
                rest = rest.substring(splitIndex)
            }
            if (rest.isNotEmpty()) {
                add(rest)
            }
        }
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

    /**
     * 翻訳拒否や指示文の復唱に見える出力の扱い。
     *
     * 判定は文字列の一致によるため、「I'm sorry」のような正しい訳文も該当しうる。
     */
    enum class RefusalLikeOutput {
        /** 失敗として扱う。原文のまま残せる場面で、訳文でない出力を差し込まないために使う */
        REJECT,

        /** 訳文として返す。原文へ戻す先が無く、正しい訳文を失敗にする方が困る場面で使う */
        ACCEPT,
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
