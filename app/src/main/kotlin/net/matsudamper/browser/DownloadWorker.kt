package net.matsudamper.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.download.DownloadRepository
import net.matsudamper.browser.download.DownloadByteFormat
import net.matsudamper.browser.download.DownloadEngine
import net.matsudamper.browser.download.DownloadFailureReason
import net.matsudamper.browser.download.DownloadFileName
import net.matsudamper.browser.download.DownloadHttpClient
import net.matsudamper.browser.download.DownloadHttpResponse
import net.matsudamper.browser.download.DownloadMediaStoreMimeType
import net.matsudamper.browser.download.DownloadMetadata
import net.matsudamper.browser.download.DownloadThumbnailLoader
import net.matsudamper.browser.download.DownloadUrl
import net.matsudamper.browser.download.GeckoDownloadHttpClient
import net.matsudamper.browser.download.PendingDownloadBodyStore
import net.matsudamper.browser.download.WebResponseDownloadResponse

/**
 * WorkManagerを使った進捗通知付きダウンロードWorker。
 * GeckoWebExecutorを使用してダウンロードすることで、GeckoViewのCookie/セッション情報が共有される。
 * 進捗・結果はRoomに書き込み、WorkManagerのprogress/outputDataは使用しない。
 * partialFileUri が指定された場合はHTTP Rangeリクエストを使って中断箇所から再開する。
 */
internal class DownloadWorker(
    private val context: Context,
    params: WorkerParameters,
    private val geckoRuntimeInitializer: GeckoRuntimeInitializer,
) : CoroutineWorker(context, params) {
    private val repository get() = DownloadRepository(context)

    /**
     * HTTP取得のクライアント。GeckoViewのCookie/セッションを共有する。
     * プロセス終了後に再実行される場合は GeckoRuntime が未生成のため、doWork で初期化を待って組み立てる。
     */
    private lateinit var httpClient: DownloadHttpClient

    private val engine = DownloadEngine()

    /** 通知タップ時に対象アイテムをハイライトするための安定したworkerID */
    private var stableWorkerId: String = ""

    /** 失敗時に保存した部分ファイルURI（doWork終了後にcatchブロックで参照） */
    private var partialResultUri: Uri? = null
    private var partialResultFileName: String = ""
    private var partialResultTotalRead: Long = 0L
    private var partialResultContentLength: Long = -1L

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val referrerUrl = inputData.getString(KEY_REFERRER_URL).orEmpty()
        val notificationId = inputData.getInt(KEY_NOTIFICATION_ID, NOTIFICATION_ID)
        val partialFileUriString = inputData.getString(KEY_PARTIAL_FILE_URI)
        stableWorkerId = inputData.getString(KEY_STABLE_WORKER_ID) ?: id.toString()

        val enqueuedAt = System.currentTimeMillis()


        ensureNotificationChannel(context)
        setForeground(createForegroundInfo(notificationId, 0, true, context.getString(R.string.download_notification_starting), 0L, -1L, stableWorkerId))

        repository.insertDownload(workerId = id.toString(), url = url, referrerUrl = referrerUrl, enqueuedAt = enqueuedAt)

        return try {
            httpClient = GeckoDownloadHttpClient(geckoRuntimeInitializer.initialize())
            // エンキュー直後にキャンセルされた場合（WorkManager 登録前のキャンセル等で
            // 割り込みが届かず Worker が起動してしまったケース）はダウンロードを開始しない
            throwIfCancelledOnRecord()
            val (fileUri, fileName) = if (partialFileUriString != null) {
                downloadFileResume(
                    urlString = url,
                    referrerUrl = referrerUrl,
                    notificationId = notificationId,
                    repository = repository,
                    partialUri = Uri.parse(partialFileUriString),
                )
            } else {
                downloadFile(url, referrerUrl, notificationId, repository)
            }
            repository.updateCompleted(id.toString(), fileName, fileUri.toString())
            postCompletionNotification(fileName, fileUri.toString(), stableWorkerId)
            Result.success()
        } catch (e: CancellationException) {
            // Job キャンセル済みのコルーチン上では Room の suspend クエリが即座に
            // CancellationException を投げて DB 更新・ファイル削除がスキップされるため、
            // NonCancellable で囲んで確実に実行する
            withContext(NonCancellable) {
                val workerId = id.toString()
                val savedUri = partialResultUri
                if (repository.isPaused(workerId)) {
                    if (savedUri != null && partialResultTotalRead > 0) {
                        // 一時停止: 部分ファイルを保持して再開（HTTP Range）に備える
                        repository.updatePausedPartial(
                            currentWorkerId = workerId,
                            partialFileUri = savedUri.toString(),
                            fileName = partialResultFileName,
                            totalRead = partialResultTotalRead,
                            contentLength = partialResultContentLength,
                        )
                        // isPaused 判定→保存の間に CANCELLED へ遷移すると updatePausedPartial が
                        // no-op になり部分ファイルが孤立するため、再確認して削除する
                        if (repository.isCancelled(workerId)) {
                            context.contentResolver.delete(savedUri, null, null)
                        }
                    } else {
                        // 部分ファイルが再開に使えない場合は削除する。
                        // PAUSED 状態は維持され、再開時は URL を再取得してダウンロードし直す
                        savedUri?.let { context.contentResolver.delete(it, null, null) }
                    }
                } else {
                    repository.updateCancelled(workerId)
                    savedUri?.let { context.contentResolver.delete(it, null, null) }
                }
            }
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            // 「失敗」表示だけでは原因が分からないため、例外の内容をレコードに残して UI・通知に出す
            val failureReason = DownloadFailureReason.from(e)
            val savedUri = partialResultUri
            if (savedUri != null && partialResultTotalRead > 0) {
                repository.updatePartialFailed(
                    currentWorkerId = id.toString(),
                    partialFileUri = savedUri.toString(),
                    fileName = partialResultFileName,
                    totalRead = partialResultTotalRead,
                    contentLength = partialResultContentLength,
                    failureReason = failureReason,
                )
            } else {
                savedUri?.let { context.contentResolver.delete(it, null, null) }
                repository.updateFailed(id.toString(), failureReason)
            }
            postFailureNotification(stableWorkerId, failureReason)
            Result.failure()
        }
    }

    /**
     * Room のレコードがキャンセル済みなら CancellationException を投げてダウンロードを中断する。
     * 管理画面のキャンセルは WorkManager の割り込みに依存せず無条件で DB を CANCELLED に
     * 更新するため、割り込みが届かないケース（WorkManager 登録前のキャンセル等）でも
     * Worker がこのチェックによって自力で停止できる
     */
    private suspend fun throwIfCancelledOnRecord() {
        if (repository.isStopRequested(id.toString())) {
            throw CancellationException("ダウンロードがキャンセルまたは一時停止されました")
        }
    }

    private suspend fun postCompletionNotification(fileName: String, fileUri: String, stableWorkerId: String) {
        val notificationId = DownloadNotificationId.complete(id)
        val openDownloadsIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_DOWNLOADS
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_WORKER_ID, stableWorkerId)
            putExtra(EXTRA_OPEN_DOWNLOADS_REQUEST_ID, "complete:$notificationId")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openDownloadsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // ダウンロード管理画面のプレビューと同じサムネイル/アプリアイコンを通知にも表示する。
        // CancellationException まで読み込み失敗扱いにすると、停止要求後もこの関数の続きが
        // 実行されて完了通知を出してしまうため、キャンセルはそのまま再送出する
        val thumbnail = try {
            DownloadThumbnailLoader.load(context, fileUri, THUMBNAIL_SIZE_PX)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(fileName)
            .setContentText(context.getString(R.string.download_notification_complete))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .apply {
                thumbnail?.bitmap?.let { setLargeIcon(it) }
            }
            .build()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, notification)
    }

    private suspend fun postFailureNotification(stableWorkerId: String, failureReason: String) {
        // フォアグラウンド通知と異なるIDを使う。
        // フォアグラウンド通知と同じIDを使うと、WorkManager がフォアグラウンドサービス停止時に
        // stopForeground(STOP_FOREGROUND_REMOVE) で同IDの通知を削除してしまうため。
        val notificationId = DownloadNotificationId.failure(id)
        val openDownloadsIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_DOWNLOADS
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_WORKER_ID, stableWorkerId)
            putExtra(EXTRA_OPEN_DOWNLOADS_REQUEST_ID, "failure:$notificationId")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openDownloadsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val fileName = repository.getByCurrentWorkerId(id)?.fileName.orEmpty()
        // 通知を開かなくても原因が分かるよう、タイトルに失敗した旨、本文に原因を表示する。
        // 原因は1行に収まらないことがあるため BigTextStyle で展開できるようにする
        val title = fileName.ifBlank { context.getString(R.string.download_notification_failed) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setSubText(context.getString(R.string.download_notification_failed))
            .setContentText(failureReason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(failureReason))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, notification)
    }

    private suspend fun downloadFile(
        urlString: String,
        referrerUrl: String,
        notificationId: Int,
        repository: DownloadRepository,
    ): Pair<Uri, String> {
        // onExternalResponse で保持した元レスポンスがあれば、そのボディを直接保存する。
        // パスワード submit(POST)・ワンタイムURL・セッション依存のダウンロードは
        // URL を GET し直すと 0 バイトになるため、元レスポンスのボディを優先する。
        val pendingResponse = PendingDownloadBodyStore.take(id.toString())
        val response: DownloadHttpResponse = if (pendingResponse != null) {
            WebResponseDownloadResponse(pendingResponse)
        } else {
            // blob: URL は生成元ドキュメントでしか解決できず、GET し直しても取得できない。
            // 何が起きたのか分かるメッセージにして、原因不明の失敗として扱わないようにする
            if (!DownloadUrl.isRefetchable(urlString)) {
                throw IOException(BLOB_NOT_REFETCHABLE_MESSAGE)
            }
            httpClient.fetch(urlString, referrerUrl, 0L)
        }

        try {
            val statusCode = response.statusCode
            if (!DownloadMetadata.isSuccessStatus(statusCode)) {
                throw IOException("HTTP エラー: $statusCode")
            }

            val body = response.body ?: throw IOException("レスポンスボディが空です。")
            val contentLength = DownloadMetadata.parseContentLength(response.header("Content-Length"))
            val mimeType = DownloadMetadata.parseMimeType(response.header("Content-Type"))
            val fileName = DownloadFileName.resolve(urlString, response.header("Content-Disposition"), mimeType)
            val mediaStoreMimeType = DownloadMediaStoreMimeType.fromFileName(fileName, mimeType)

            setForeground(createForegroundInfo(notificationId, 0, contentLength <= 0, fileName, 0L, contentLength, stableWorkerId))
            repository.updateProgress(id.toString(), fileName, 0, 0L, contentLength)

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mediaStoreMimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("ダウンロードエントリの作成に失敗しました。")

            partialResultUri = uri
            partialResultFileName = fileName
            partialResultContentLength = contentLength

            var lastUpdateTime = 0L
            resolver.openOutputStream(uri)?.use { outputStream ->
                engine.copyTo(
                    body = body,
                    sink = outputStream,
                    expectedTotalLength = contentLength,
                    startBytes = 0L,
                ) { totalRead ->
                    // 失敗時の部分ファイルサイズ把握のため累計バイト数は毎回更新する
                    partialResultTotalRead = totalRead
                    // 通知・Room更新のみレート制限する
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= PROGRESS_UPDATE_INTERVAL_MILLIS) {
                        // WorkManager の割り込みが取りこぼされても確実に停止できるよう、
                        // DB のキャンセル状態を確認して自力で中断する
                        throwIfCancelledOnRecord()
                        val progress = if (contentLength > 0) (totalRead * 100 / contentLength).toInt() else 0
                        repository.updateProgress(id.toString(), fileName, progress, totalRead, contentLength)
                        setForeground(createForegroundInfo(notificationId, progress, contentLength <= 0, fileName, totalRead, contentLength, stableWorkerId))
                        lastUpdateTime = now
                    }
                }
            } ?: throw IOException("出力ストリームを開けませんでした。")

            val completeValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, completeValues, null, null)
            partialResultUri = null
            // IS_PENDING=0 更新後にMediaStoreが重複を避けてリネームした場合に備え、実際のファイル名を取得する
            val actualFileName = resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            return Pair(uri, actualFileName ?: fileName)
        } finally {
            response.close()
        }
    }

    /**
     * HTTP Rangeリクエストを使って中断箇所からダウンロードを再開する。
     * サーバーが206 Partial Contentを返した場合のみ再開し、200 OKの場合は最初からやり直す。
     */
    private suspend fun downloadFileResume(
        urlString: String,
        referrerUrl: String,
        notificationId: Int,
        repository: DownloadRepository,
        partialUri: Uri,
    ): Pair<Uri, String> {
        val resolver = context.contentResolver

        // 再開は必ずURLの再取得を伴うため、再取得できないURLはここで止める。
        // UI からは再開ボタンを出していないが、UI 以外の経路でも不正な再取得を始めないようにする
        if (!DownloadUrl.isRefetchable(urlString)) {
            throw IOException(BLOB_NOT_REFETCHABLE_MESSAGE)
        }

        // 部分ファイルの実際のサイズを取得する（DBの値と一致しない場合に備える）
        val actualFileSize = resolver.openFileDescriptor(partialUri, "r")?.use { it.statSize } ?: 0L
        val rangeStart = actualFileSize

        val response = httpClient.fetch(urlString, referrerUrl, rangeStart)
        try {
            val statusCode = response.statusCode

            // サーバーがRangeリクエストをサポートしていない場合（200 OK）は最初からやり直す。
            // 非HTTP（statusCode が無い）レスポンスも Range 継続はできないため同様に扱う
            if (statusCode == 200 || statusCode == DownloadMetadata.NO_HTTP_STATUS) {
                resolver.delete(partialUri, null, null)
                partialResultUri = null
                return downloadFile(urlString, referrerUrl, notificationId, repository)
            }

            if (statusCode != 206) {
                throw IOException("HTTP エラー: $statusCode")
            }

            val body = response.body ?: throw IOException("レスポンスボディが空です。")
            val contentRangeHeader = response.header("Content-Range")
            val totalFileSize = DownloadMetadata.parseTotalFromContentRange(contentRangeHeader)
                ?: (rangeStart + DownloadMetadata.parseContentLength(response.header("Content-Length")))
            val contentLength = totalFileSize
            val mimeType = DownloadMetadata.parseMimeType(response.header("Content-Type"))
            val fileName = DownloadFileName.resolve(urlString, response.header("Content-Disposition"), mimeType)

            setForeground(
                createForegroundInfo(
                    notificationId,
                    if (contentLength >
                        0
                    ) {
                        (rangeStart * 100 / contentLength).toInt()
                    } else {
                        0
                    },
                    contentLength <= 0,
                    fileName,
                    rangeStart,
                    contentLength,
                    stableWorkerId,
                ),
            )

            // 部分ファイルへの追記用に IS_PENDING を確認・維持する
            val pendingValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 1) }
            resolver.update(partialUri, pendingValues, null, null)

            partialResultUri = partialUri
            partialResultFileName = fileName
            partialResultContentLength = contentLength
            partialResultTotalRead = rangeStart

            var lastUpdateTime = 0L
            resolver.openOutputStream(partialUri, "wa")?.use { outputStream ->
                engine.copyTo(
                    body = body,
                    sink = outputStream,
                    expectedTotalLength = contentLength,
                    startBytes = rangeStart,
                ) { totalRead ->
                    partialResultTotalRead = totalRead
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= PROGRESS_UPDATE_INTERVAL_MILLIS) {
                        // WorkManager の割り込みが取りこぼされても確実に停止できるよう、
                        // DB のキャンセル状態を確認して自力で中断する
                        throwIfCancelledOnRecord()
                        val progress = if (contentLength > 0) (totalRead * 100 / contentLength).toInt() else 0
                        repository.updateProgress(id.toString(), fileName, progress, totalRead, contentLength)
                        setForeground(createForegroundInfo(notificationId, progress, contentLength <= 0, fileName, totalRead, contentLength, stableWorkerId))
                        lastUpdateTime = now
                    }
                }
            } ?: throw IOException("出力ストリームを開けませんでした。")

            val completeValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(partialUri, completeValues, null, null)
            partialResultUri = null
            return Pair(partialUri, fileName)
        } finally {
            response.close()
        }
    }

    private fun createForegroundInfo(
        notificationId: Int,
        progress: Int,
        indeterminate: Boolean,
        title: String,
        totalRead: Long,
        contentLength: Long,
        stableWorkerId: String,
    ): ForegroundInfo {
        val sizeText = buildSizeText(totalRead, contentLength)
        val openDownloadsIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_DOWNLOADS
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_WORKER_ID, stableWorkerId)
            putExtra(EXTRA_OPEN_DOWNLOADS_REQUEST_ID, "progress:$notificationId")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openDownloadsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelPendingIntent = DownloadCancelReceiver.createPendingIntent(
            context = context,
            currentWorkerId = id,
            stableWorkerId = stableWorkerId,
            notificationId = notificationId,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(sizeText)
            .setProgress(100, progress, indeterminate)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.download_notification_cancel),
                cancelPendingIntent,
            )
            .build()
        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        /** blob: URL を取得し直そうとした場合の失敗理由 */
        private const val BLOB_NOT_REFETCHABLE_MESSAGE = "ページ内で生成された一時データ (blob) のため、取得し直せません"

        /** 進捗（通知・Room）の更新間隔。頻繁な更新を避けるためのレート制限 */
        private const val PROGRESS_UPDATE_INTERVAL_MILLIS = 1000L

        /** 完了通知の largeIcon に使うサムネイル/アプリアイコンの最大サイズ (px) */
        private const val THUMBNAIL_SIZE_PX = 256

        const val KEY_URL = "url"
        const val KEY_REFERRER_URL = "referrer_url"
        const val KEY_NOTIFICATION_ID = "notification_id"

        const val KEY_PARTIAL_FILE_URI = "partial_file_uri"

        const val KEY_RESUME_FROM_BYTES = "resume_from_bytes"
        const val CHANNEL_ID = "download_progress_channel"
        const val NOTIFICATION_ID = 9001
        const val TAG_DOWNLOAD = "download"

        const val ACTION_OPEN_DOWNLOADS = "net.matsudamper.browser.ACTION_OPEN_DOWNLOADS"

        /** 通知タップ時にハイライト対象のダウンロードを特定するためのExtra */
        const val EXTRA_WORKER_ID = "net.matsudamper.browser.EXTRA_WORKER_ID"

        /** 通知インスタンスごとに一意な要求 ID（進捗・完了・失敗を区別） */
        const val EXTRA_OPEN_DOWNLOADS_REQUEST_ID = "net.matsudamper.browser.EXTRA_OPEN_DOWNLOADS_REQUEST_ID"

        /** 再開時にも安定した workerId を Worker 内で参照するための InputData キー */
        const val KEY_STABLE_WORKER_ID = "stable_worker_id"

        /**
         * バイト数を適切な単位（B/KB/MB）の文字列に変換する。
         * contentLength > 0 の場合は「転送済み / 総サイズ」形式で返す。
         */
        fun buildSizeText(totalRead: Long, contentLength: Long): String {
            return if (contentLength > 0) {
                "${formatBytes(totalRead)} / ${formatBytes(contentLength)}"
            } else {
                formatBytes(totalRead)
            }
        }

        fun formatBytes(bytes: Long): String = DownloadByteFormat.format(bytes)

        fun ensureNotificationChannel(context: Context) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "ダウンロード",
                    NotificationManager.IMPORTANCE_LOW,
                )
                notificationManager.createNotificationChannel(channel)
            }
        }
    }
}
