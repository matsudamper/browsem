package net.matsudamper.browser

/**
 * Web Share API (navigator.share) の共有データを OS 共有シート向けの本文に組み立てる。
 */
internal fun buildWebShareBody(text: String?, uri: String?): String {
    return listOfNotNull(
        text?.trim()?.takeIf { it.isNotEmpty() },
        uri?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString("\n")
}
