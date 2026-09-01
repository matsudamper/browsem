package net.matsudamper.browser.feature.websharefiles

object WebShareFilesLimits {
    const val MAX_FILES = 10
    const val MAX_FILE_BYTES = 5 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 10 * 1024 * 1024

    fun maxBase64CharsPerFile(): Int = 4 * ((MAX_FILE_BYTES + 2) / 3)

    fun estimateDecodedBytes(base64: String): Int {
        var padding = 0
        if (base64.endsWith("==")) {
            padding = 2
        } else if (base64.endsWith("=")) {
            padding = 1
        }
        return (base64.length * 3) / 4 - padding
    }
}
