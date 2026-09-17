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
        val existingDownloads: List<DuplicateDownloadEntry>,
        internal val onConfirm: () -> Unit,
        internal val onDismiss: () -> Unit = {},
        internal val onCancel: () -> Unit = {},
    )

    data class DuplicateDownloadEntry(
        val fileName: String,
        val status: DownloadRecordStatus,
        val fileUri: String?,
    )

    var pendingDownloadResponse by mutableStateOf<WebResponse?>(null)
        private set

    var duplicateDownloadState by mutableStateOf<DuplicateDownloadState?>(null)
        private set

    fun downloadImage(imageUrl: String) {
        val referrerUrl = currentPageUrl()
        coroutineScope.launch {
            val duplicates = findDuplicates(imageUrl)
            if (duplicates != null) {
                duplicateDownloadState = DuplicateDownloadState(
                    url = imageUrl,
                    existingDownloads = duplicates,
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
            val duplicates = findDuplicates(response.uri)
            if (duplicates != null) {
                duplicateDownloadState = DuplicateDownloadState(
                    url = response.uri,
                    existingDownloads = duplicates,
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

    /** 既存ダウンロードがない場合は null を返す */
    private suspend fun findDuplicates(url: String): List<DuplicateDownloadEntry>? {
        val duplicates = geckoDownloadManager.findDuplicateDownloads(url)
        if (duplicates.isEmpty()) return null
        return duplicates.map { record ->
            DuplicateDownloadEntry(
                fileName = record.fileName,
                status = record.status,
                fileUri = record.fileUri,
            )
        }
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
}
