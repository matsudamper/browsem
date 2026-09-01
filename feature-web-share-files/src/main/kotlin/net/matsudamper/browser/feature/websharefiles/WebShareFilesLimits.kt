package net.matsudamper.browser.feature.websharefiles

object WebShareFilesLimits {
    const val MAX_FILES = 10
    const val MAX_FILE_BYTES = 5 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 10 * 1024 * 1024

    fun maxBase64CharsPerFile(): Int = ((MAX_FILE_BYTES.toLong() * 4L + 2L) / 3L).toInt()

    fun estimateDecodedBytes(base64Length: Int): Int = (base64Length * 3) / 4
}
