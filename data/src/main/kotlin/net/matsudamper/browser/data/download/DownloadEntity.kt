package net.matsudamper.browser.data.download

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download",
    indices = [Index(value = ["currentWorkerId"])],
)
data class DownloadEntity(
    /** レコードの安定ID。初回ダウンロード時のワーカーIDで、再開してもこの値は変わらない */
    @PrimaryKey val workerId: String,
    /** 現在実行中（または最後に実行した）WorkManagerワーカーのID。再開のたびに更新される */
    val currentWorkerId: String,
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
    /** ダウンロード失敗の原因。UI・通知に表示する。失敗していない場合は null */
    val failureReason: String? = null,
)
