package net.matsudamper.browser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.download.DownloadRecordStatus
import net.matsudamper.browser.download.proceedDownloadFromResponse
import org.mozilla.geckoview.WebResponse

/**
 * タブ 1 つあたりのダウンロード確認ダイアログ状態。
 *
 * 同一 URL の既存ダウンロードがある場合は重複ダイアログを、なければ通常の確認ダイアログを表示し、
 * ユーザーの確認後に [GeckoDownloadManager] へ登録する。
 */
@Stable
internal class TabDownloadState(
    private val coroutineScope: CoroutineScope,
    private val geckoDownloadManager: GeckoDownloadManager,
    private val currentPageUrl: () -> String,
    private val onRequestNotificationPermission: suspend () -> Unit,
    private val onDownloadResolved: (responseUri: String) -> Unit,
) {
    @Stable
    class DuplicateDownloadState(
        val url: String,
        val existingDownload: ExistingDownload,
        internal val onConfirm: () -> Unit,
        internal val onDismiss: () -> Unit = {},
        internal val onCancel: () -> Unit = {},
    )

    sealed interface ExistingDownload {
        val fileName: String

        data class Succeeded(
            override val fileName: String,
            val fileUri: String?,
        ) : ExistingDownload

        data class InProgress(
            override val fileName: String,
        ) : ExistingDownload
    }

    var pendingDownloadResponse by mutableStateOf<WebResponse?>(null)
        private set

    var duplicateDownloadState by mutableStateOf<DuplicateDownloadState?>(null)
        private set

    fun downloadImage(imageUrl: String) {
        val referrerUrl = currentPageUrl()
        coroutineScope.launch {
            val duplicate = findExistingDownload(imageUrl)
            if (duplicate != null) {
                duplicateDownloadState = DuplicateDownloadState(
                    url = imageUrl,
                    existingDownload = duplicate,
                    onConfirm = { proceedDownloadImage(imageUrl, referrerUrl) },
                )
                return@launch
            }
            proceedDownloadImage(imageUrl, referrerUrl)
        }
    }

    // GeckoViewがレンダリングできないレスポンス（ダウンロードリンク等）を受け取った際に呼ばれる
    // 重複がある場合は重複ダイアログを直接表示し、なければ通常の確認ダイアログを表示する
    fun downloadFileFromResponse(response: WebResponse) {
        val referrerUrl = currentPageUrl()
        coroutineScope.launch {
            val duplicate = findExistingDownload(response.uri)
            if (duplicate != null) {
                duplicateDownloadState = DuplicateDownloadState(
                    url = response.uri,
                    existingDownload = duplicate,
                    onConfirm = {
                        proceedDownloadFromResponse(response, referrerUrl) {
                            onDownloadResolved(response.uri)
                        }
                    },
                    onDismiss = {
                        response.body?.close()
                    },
                    onCancel = {
                        onDownloadResolved(response.uri)
                    },
                )
                return@launch
            }
            pendingDownloadResponse = response
        }
    }

    fun confirmPendingDownload() {
        val response = pendingDownloadResponse ?: return
        pendingDownloadResponse = null
        proceedDownloadFromResponse(response, currentPageUrl()) {
            onDownloadResolved(response.uri)
        }
    }

    fun cancelPendingDownload() {
        val response = pendingDownloadResponse
        pendingDownloadResponse?.body?.close()
        pendingDownloadResponse = null
        response?.let { onDownloadResolved(it.uri) }
    }

    fun dismissPendingDownload() {
        pendingDownloadResponse?.body?.close()
        pendingDownloadResponse = null
    }

    fun confirmDuplicateDownload() {
        val state = duplicateDownloadState ?: return
        duplicateDownloadState = null
        state.onConfirm()
    }

    fun cancelDuplicateDownload() {
        val state = duplicateDownloadState ?: return
        duplicateDownloadState = null
        state.onDismiss()
        state.onCancel()
    }

    fun dismissDuplicateDownload() {
        val state = duplicateDownloadState ?: return
        duplicateDownloadState = null
        state.onDismiss()
    }

    /** 完了済みを優先して最新の 1 件を返す。完了済みもダウンロード中もなければ null */
    private suspend fun findExistingDownload(url: String): ExistingDownload? {
        val records = geckoDownloadManager.findDuplicateDownloads(url)
        val latestSucceeded = records
            .filter { it.status == DownloadRecordStatus.SUCCEEDED }
            .maxByOrNull { it.enqueuedAt }
        if (latestSucceeded != null) {
            return ExistingDownload.Succeeded(
                fileName = latestSucceeded.fileName,
                fileUri = latestSucceeded.fileUri,
            )
        }
        val latestInProgress = records
            .filter { it.status in IN_PROGRESS_STATUSES }
            .maxByOrNull { it.enqueuedAt }
            ?: return null
        return ExistingDownload.InProgress(fileName = latestInProgress.fileName)
    }

    private fun proceedDownloadImage(imageUrl: String, referrerUrl: String) {
        coroutineScope.launch {
            onRequestNotificationPermission()
            geckoDownloadManager.enqueueDownload(
                url = imageUrl,
                referrerUrl = referrerUrl,
                coroutineScope = coroutineScope,
            )
        }
    }

    private fun proceedDownloadFromResponse(
        response: WebResponse,
        referrerUrl: String,
        onEnqueued: () -> Unit,
    ) {
        coroutineScope.launch {
            proceedDownloadFromResponse(
                awaitPermission = { onRequestNotificationPermission() },
                enqueue = { geckoDownloadManager.enqueueDownloadFromResponse(response, referrerUrl) },
                onEnqueued = onEnqueued,
                onEnqueueFailed = { response.body?.close() },
            )
        }
    }

    private companion object {
        val IN_PROGRESS_STATUSES = setOf(
            DownloadRecordStatus.ENQUEUED,
            DownloadRecordStatus.RUNNING,
            DownloadRecordStatus.PAUSED,
        )
    }
}
