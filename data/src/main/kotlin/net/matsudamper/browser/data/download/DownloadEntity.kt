package net.matsudamper.browser.data.download

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download")
data class DownloadEntity(
    @PrimaryKey val workerId: String,
    val url: String,
    val fileName: String,
    val fileUri: String?,
    /** DownloadRecordStatus.name を格納する */
    val status: String,
    val progress: Int,
    val totalRead: Long,
    val contentLength: Long,
    val enqueuedAt: Long,
    /** ダウンロード再開に使用するリファラーURL */
    val referrerUrl: String = "",
    /** ダウンロード失敗時に保存した部分ファイルのMediaStore URI。再開時にRangeリクエストで使用 */
    val partialFileUri: String? = null,
)
