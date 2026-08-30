package net.matsudamper.browser

import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.matsudamper.browser.feature.networklog.NetworkLogEntry
import net.matsudamper.browser.feature.networklog.NetworkResourceType

/**
 * ネットワークログの表示用フォーマット。
 * 単体テストしやすいよう Android 非依存の純粋関数として切り出している。
 */
internal object NetworkLogFormat {
    private const val BYTES_PER_UNIT = 1024.0
    private const val MILLIS_PER_SECOND = 1000.0

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)

    /** バイト数を人が読める単位にする。不明な場合は "-" */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "-"
        if (bytes < BYTES_PER_UNIT) return "$bytes B"
        var value = bytes / BYTES_PER_UNIT
        val units = listOf("KB", "MB", "GB")
        units.forEachIndexed { index, unit ->
            if (value < BYTES_PER_UNIT || index == units.lastIndex) {
                return String.format(Locale.US, "%.1f %s", value, unit)
            }
            value /= BYTES_PER_UNIT
        }
        return "$bytes B"
    }

    /** 所要時間を表示用にする */
    fun formatDuration(millis: Long): String {
        if (millis < 0) return "-"
        if (millis < MILLIS_PER_SECOND) return "$millis ms"
        return String.format(Locale.US, "%.2f s", millis / MILLIS_PER_SECOND)
    }

    /** 開始時刻を HH:mm:ss.SSS で表示する */
    fun formatTime(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        if (epochMillis <= 0) return "-"
        return timeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))
    }

    /** URL からファイル名相当の短い表示名を取り出す */
    fun displayName(url: String): String {
        if (url.isEmpty()) return "-"
        if (url.startsWith("data:")) return url.take(DATA_URL_NAME_LENGTH)
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val name = path.trimEnd('/').substringAfterLast('/')
        return name.ifEmpty { hostOf(url).ifEmpty { url } }
    }

    /** URL からホスト名を取り出す。取り出せない場合は空文字 */
    fun hostOf(url: String): String {
        return runCatching { URI(url).host }.getOrNull().orEmpty()
    }

    /** ステータスの区分を判定する */
    fun statusKind(statusCode: Int, error: String?): NetworkLogUiState.StatusKind {
        if (error != null) return NetworkLogUiState.StatusKind.Failed
        return when (statusCode) {
            in SUCCESS_RANGE -> NetworkLogUiState.StatusKind.Success
            in REDIRECT_RANGE -> NetworkLogUiState.StatusKind.Redirect
            in CLIENT_ERROR_RANGE -> NetworkLogUiState.StatusKind.ClientError
            in SERVER_ERROR_RANGE -> NetworkLogUiState.StatusKind.ServerError
            else -> NetworkLogUiState.StatusKind.Pending
        }
    }

    /** 一覧に出すステータス表示 */
    fun statusLabel(statusCode: Int, error: String?): String {
        return when {
            error != null -> "失敗"
            statusCode > 0 -> statusCode.toString()
            else -> "-"
        }
    }

    /** リソース種別の日本語ラベル */
    fun typeLabel(type: NetworkResourceType): String {
        return when (type) {
            NetworkResourceType.Document -> "文書"
            NetworkResourceType.Stylesheet -> "CSS"
            NetworkResourceType.Script -> "JS"
            NetworkResourceType.Image -> "画像"
            NetworkResourceType.Media -> "メディア"
            NetworkResourceType.Font -> "フォント"
            NetworkResourceType.Xhr -> "XHR"
            NetworkResourceType.Other -> "その他"
        }
    }

    /** 絞り込みチップのラベル */
    fun filterLabel(filter: NetworkLogUiState.ResourceFilter): String {
        return when (filter) {
            NetworkLogUiState.ResourceFilter.All -> "すべて"
            NetworkLogUiState.ResourceFilter.Document -> "文書"
            NetworkLogUiState.ResourceFilter.Stylesheet -> "CSS"
            NetworkLogUiState.ResourceFilter.Script -> "JS"
            NetworkLogUiState.ResourceFilter.Image -> "画像"
            NetworkLogUiState.ResourceFilter.Media -> "メディア"
            NetworkLogUiState.ResourceFilter.Font -> "フォント"
            NetworkLogUiState.ResourceFilter.Xhr -> "XHR"
            NetworkLogUiState.ResourceFilter.Other -> "その他"
        }
    }

    /** リソース種別に対応する絞り込み条件 */
    fun filterOf(type: NetworkResourceType): NetworkLogUiState.ResourceFilter {
        return when (type) {
            NetworkResourceType.Document -> NetworkLogUiState.ResourceFilter.Document
            NetworkResourceType.Stylesheet -> NetworkLogUiState.ResourceFilter.Stylesheet
            NetworkResourceType.Script -> NetworkLogUiState.ResourceFilter.Script
            NetworkResourceType.Image -> NetworkLogUiState.ResourceFilter.Image
            NetworkResourceType.Media -> NetworkLogUiState.ResourceFilter.Media
            NetworkResourceType.Font -> NetworkLogUiState.ResourceFilter.Font
            NetworkResourceType.Xhr -> NetworkLogUiState.ResourceFilter.Xhr
            NetworkResourceType.Other -> NetworkLogUiState.ResourceFilter.Other
        }
    }

    /** 絞り込み条件と検索文字列に一致するかどうか */
    fun matches(
        entry: NetworkLogEntry,
        filter: NetworkLogUiState.ResourceFilter,
        query: String,
    ): Boolean {
        val filterMatched = filter == NetworkLogUiState.ResourceFilter.All ||
            filterOf(entry.resourceType) == filter
        if (!filterMatched) return false
        return query.isBlank() || entry.url.contains(query.trim(), ignoreCase = true)
    }

    private const val DATA_URL_NAME_LENGTH = 40
    private val SUCCESS_RANGE = 200..299
    private val REDIRECT_RANGE = 300..399
    private val CLIENT_ERROR_RANGE = 400..499
    private val SERVER_ERROR_RANGE = 500..599
}
