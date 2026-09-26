package net.matsudamper.browser.semanticsearch

import net.matsudamper.browser.translate.PageTranslationWebExtension

internal object SemanticSearchCandidates {
    /**
     * 推論コストの上限のため、スキャン済みセグメントの先頭から渡す。
     * スキャン側で画面に近い順に並んでいる。クエリによる絞り込みは行わない。
     */
    fun selectForInference(
        segments: List<PageTranslationWebExtension.Segment>,
        maxCandidates: Int,
    ): List<PageTranslationWebExtension.Segment> {
        if (segments.isEmpty() || maxCandidates <= 0) return listOf()
        return segments.take(maxCandidates)
    }
}

internal fun parseSemanticSearchSegmentIds(
    modelOutput: String,
    allowedIds: Set<String>,
): List<String> {
    if (modelOutput.contains("NONE", ignoreCase = true)) return listOf()
    val pattern = Regex("""[ta]\d+""")
    val ordered = linkedSetOf<String>()
    pattern.findAll(modelOutput).forEach { match ->
        val id = match.value
        if (allowedIds.contains(id)) {
            ordered.add(id)
        }
    }
    return ordered.toList()
}

internal fun buildSemanticSearchPrompt(
    query: String,
    candidates: List<PageTranslationWebExtension.Segment>,
    maxSegmentChars: Int,
): String {
    return buildString {
        appendLine("ページ内意味検索。ユーザーの質問に意味的に合うセグメントIDだけを選ぶ。")
        appendLine("質問の語が本文に無くても、意図が合うセグメントを選ぶ（例: 質問「価格」→「900円」「1,200円」など）。")
        appendLine("質問語の単純な文字列一致だけを選ばない。")
        appendLine("出力はマッチしたIDをカンマ区切りのみ。該当なしは NONE のみ。")
        appendLine()
        appendLine("質問: ${query.trim()}")
        appendLine("候補:")
        candidates.forEach { segment ->
            val text = segment.text.take(maxSegmentChars)
            appendLine("${segment.id}: $text")
        }
    }
}
