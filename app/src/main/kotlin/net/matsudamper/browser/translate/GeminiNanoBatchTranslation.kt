package net.matsudamper.browser.translate

import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide

/**
 * 複数セグメントをまとめて訳すときの構造化出力。
 *
 * ML Kit GenAI の構造化出力へ渡すため、genai-schema の KSP プロセッサがスキーマを生成する。
 */
@Generable(description = "Translations of the numbered source segments.")
data class GeminiNanoTranslatedSegments(
    @Guide(description = "Exactly one entry per source segment, keeping the same index numbers.")
    val translations: List<GeminiNanoTranslatedSegment>,
)

@Generable(description = "Translation of one numbered source segment.")
data class GeminiNanoTranslatedSegment(
    @Guide(description = "Index number of the source segment as given in the input.")
    val index: Int,
    @Guide(description = "Translation of that segment only, without the index or any explanation.")
    val text: String,
)

/** 改行を含む文は番号付きの一覧にすると区切りが崩れるため、1 件ずつの経路に回す */
internal fun isBatchTranslatableText(text: String): Boolean =
    text.length <= BATCH_TRANSLATION_CHAR_LIMIT && !text.contains('\n')

/**
 * 連続するセグメントを、1 回の生成で訳せる量ごとにまとめる。
 *
 * まとめ翻訳を使わない場合や、まとめられないセグメントは 1 件ずつのグループになる。
 * 1 グループが生成 1 回に対応するので、訳文の反映もグループ単位で行える。
 */
internal fun groupSegmentsForBatchTranslation(
    segments: List<PageTranslationWebExtension.Segment>,
    batchTranslationEnabled: Boolean,
): List<List<PageTranslationWebExtension.Segment>> {
    if (!batchTranslationEnabled) return segments.map { listOf(it) }
    return buildList {
        var current = mutableListOf<PageTranslationWebExtension.Segment>()
        var currentChars = 0
        fun flush() {
            if (current.isNotEmpty()) {
                add(current)
                current = mutableListOf()
                currentChars = 0
            }
        }
        for (segment in segments) {
            if (!isBatchTranslatableText(segment.text)) {
                flush()
                add(listOf(segment))
                continue
            }
            val exceedsLimit = current.size >= BATCH_TRANSLATION_SEGMENT_LIMIT ||
                currentChars + segment.text.length > BATCH_TRANSLATION_CHAR_LIMIT
            if (exceedsLimit) flush()
            current.add(segment)
            currentChars += segment.text.length
        }
        flush()
    }
}

internal fun buildGeminiNanoBatchTranslationInstruction(
    sourceLanguage: String,
    targetLanguage: String,
): String {
    val sourceName = toLanguageDisplayName(sourceLanguage)
    val targetName = toLanguageDisplayName(targetLanguage)
    return "The user text is a numbered list of independent segments in $sourceName. " +
        "Translate each segment into $targetName and return exactly one entry per segment " +
        "with the same index number. " +
        "If a segment cannot be translated, return it unchanged and never explain why."
}

internal fun buildGeminiNanoBatchTranslationInput(texts: List<String>): String =
    texts.withIndex().joinToString("\n") { (index, text) -> "${index + 1}: $text" }

/**
 * 構造化出力の各エントリを入力順に並べ直す。
 *
 * index の欠落・重複・範囲外や、訳文として使えないエントリが 1 つでもあれば
 * まとめ翻訳全体を採用せず null を返し、呼び出し元が 1 件ずつの経路へ戻す。
 */
internal fun orderBatchTranslations(
    sourceCount: Int,
    translations: List<GeminiNanoTranslatedSegment>,
): List<String>? {
    if (translations.size != sourceCount) return null
    val ordered = arrayOfNulls<String>(sourceCount)
    for (translation in translations) {
        val position = translation.index - 1
        if (position !in 0 until sourceCount || ordered[position] != null) return null
        val text = sanitizeTranslatedText(translation.text)
        if (text.isBlank() || isUnusableTranslation(text)) return null
        ordered[position] = text
    }
    return ordered.map { it ?: return null }
}

/** 出力上限に収まるよう、1 回の生成に入れる合計文字数と件数を抑える */
internal const val BATCH_TRANSLATION_CHAR_LIMIT = 300
internal const val BATCH_TRANSLATION_SEGMENT_LIMIT = 8
