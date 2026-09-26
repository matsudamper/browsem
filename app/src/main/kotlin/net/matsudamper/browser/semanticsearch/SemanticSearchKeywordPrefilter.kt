package net.matsudamper.browser.semanticsearch

import net.matsudamper.browser.translate.PageTranslationWebExtension

internal object SemanticSearchKeywordPrefilter {
    fun selectCandidates(
        segments: List<PageTranslationWebExtension.Segment>,
        query: String,
        maxCandidates: Int,
    ): List<PageTranslationWebExtension.Segment> {
        if (segments.isEmpty() || maxCandidates <= 0) return listOf()
        val terms = query
            .lowercase()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
        if (terms.isEmpty()) {
            return segments.take(maxCandidates)
        }
        return segments
            .map { segment ->
                val lowered = segment.text.lowercase()
                val score = terms.count { term -> lowered.contains(term) }
                segment to score
            }
            .sortedWith(
                compareByDescending<Pair<PageTranslationWebExtension.Segment, Int>> { it.second }
                    .thenBy { it.first.text.length },
            )
            .take(maxCandidates)
            .map { it.first }
    }
}

internal fun parseSemanticSearchSegmentIds(
    modelOutput: String,
    allowedIds: Set<String>,
): List<String> {
    if (modelOutput.contains("NONE", ignoreCase = true)) return listOf()
    val pattern = Regex("""t\d+""")
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
