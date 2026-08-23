package net.matsudamper.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import org.koin.compose.koinInject
import org.mozilla.geckoview.GeckoSession

/**
 * ネットワークログ画面の UiState を組み立てる。
 * 通信ログの収集自体は [NetworkLogWebExtension] が常時行っており、
 * ここでは表示中のタブの分を絞り込んで表示に変換する。
 */
@Composable
internal fun rememberNetworkLogUiState(
    session: GeckoSession,
    onDismiss: () -> Unit,
): NetworkLogUiState {
    val context = LocalContext.current
    val store: NetworkLogStore = koinInject()
    val extension: NetworkLogWebExtension = koinInject()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val holder = remember(session) {
        NetworkLogStateHolder(
            extension = extension,
            store = store,
            context = context,
            onDismiss = { currentOnDismiss() },
        )
    }
    val entries by store.entries.collectAsState()
    val tabIds by extension.sessionTabIds.collectAsState()
    val tabId = tabIds[session]

    // 詳細を開いた時と再取得を押した時にプレビューを読み込む
    LaunchedEffect(holder, holder.selectedId, holder.previewReloadCount) {
        holder.loadPreview()
    }
    return holder.createUiState(allEntries = entries, tabId = tabId)
}

/**
 * ネットワークログ画面の状態保持。
 */
@Stable
internal class NetworkLogStateHolder(
    private val extension: NetworkLogWebExtension,
    private val store: NetworkLogStore,
    private val context: Context,
    private val onDismiss: () -> Unit,
) {
    private var filter by mutableStateOf(NetworkLogUiState.ResourceFilter.All)
    private var searchQuery by mutableStateOf("")

    /** 詳細表示中のリクエスト ID。一覧表示中は null */
    var selectedId by mutableStateOf<String?>(null)
        private set

    /** プレビュー再取得のトリガー */
    var previewReloadCount by mutableIntStateOf(0)
        private set

    private var preview by mutableStateOf<NetworkLogUiState.Preview>(
        NetworkLogUiState.Preview.Loading,
    )

    // コピー用に保持する取得済みの本文
    private var previewText: String? = null

    // 表示対象のタブ ID。null の場合は全タブ分を表示する
    private var currentTabId: Int? = null

    private val callbacks = object : NetworkLogUiState.Callbacks {
        override fun onClickFilter(filter: NetworkLogUiState.ResourceFilter) {
            this@NetworkLogStateHolder.filter = filter
        }

        override fun onSearchQueryChange(query: String) {
            searchQuery = query
        }

        override fun onClickEntry(id: String) {
            selectedId = id
            preview = NetworkLogUiState.Preview.Loading
            previewText = null
        }

        override fun onClickCloseDetail() {
            selectedId = null
            previewText = null
        }

        override fun onClickCopyUrl() {
            val url = selectedEntry()?.url ?: return
            copyToClipboard(label = "URL", text = url, message = "URL をコピーしました")
        }

        override fun onClickCopyBody() {
            val text = previewText ?: return
            copyToClipboard(label = "body", text = text, message = "本文をコピーしました")
        }

        override fun onClickReloadPreview() {
            preview = NetworkLogUiState.Preview.Loading
            previewText = null
            previewReloadCount += 1
        }

        override fun onClickClear() {
            // タブを特定できていない間は全タブ分を表示しているため、
            // ここでクリアすると他のタブのログまで消えてしまう
            val tabId = currentTabId ?: return
            store.clear(tabId)
            selectedId = null
        }

        override fun onDismiss() {
            onDismiss.invoke()
        }
    }

    /** 表示用の UiState を組み立てる */
    fun createUiState(allEntries: List<NetworkLogEntry>, tabId: Int?): NetworkLogUiState {
        currentTabId = tabId
        val tabEntries = if (tabId == null) {
            allEntries
        } else {
            allEntries.filter { it.tabId == tabId }
        }
        val filtered = tabEntries.filter {
            NetworkLogFormat.matches(entry = it, filter = filter, query = searchQuery)
        }
        val detail = selectedId
            ?.let { id -> tabEntries.firstOrNull { it.requestId == id } }
            ?.toDetail()
        return NetworkLogUiState(
            callbacks = callbacks,
            entries = filtered.asReversed().map { it.toUiStateEntry() },
            filters = createFilters(tabEntries),
            searchQuery = searchQuery,
            summary = NetworkLogUiState.Summary(
                countLabel = "${filtered.size} 件",
                sizeLabel = NetworkLogFormat.formatBytes(filtered.sumOf { it.sizeBytes }),
            ),
            notice = if (tabId == null) {
                "タブを特定中のため、すべてのタブの通信を表示しています"
            } else {
                null
            },
            canClear = tabId != null,
            detail = detail,
        )
    }

    /** プレビュー用の本文を取得する */
    fun loadPreview() {
        val id = selectedId ?: return
        val entry = selectedEntry()
        if (entry == null) {
            // ログが消去された等でエントリが無い場合、待ち続けないよう即座に確定させる
            preview = NetworkLogUiState.Preview.Unavailable(
                message = "この通信のログは既に破棄されています",
            )
            return
        }
        preview = NetworkLogUiState.Preview.Loading
        extension.requestBody(requestId = id, url = entry.url, method = entry.method) { body ->
            // 取得中に別の項目へ切り替わっている場合は破棄する
            if (selectedId != id) return@requestBody
            preview = body.toPreview()
        }
    }

    private fun selectedEntry(): NetworkLogEntry? {
        val id = selectedId ?: return null
        return store.entries.value.firstOrNull { it.requestId == id }
    }

    private fun createFilters(entries: List<NetworkLogEntry>): List<NetworkLogUiState.Filter> {
        return NetworkLogUiState.ResourceFilter.entries.mapNotNull { candidate ->
            val count = if (candidate == NetworkLogUiState.ResourceFilter.All) {
                entries.size
            } else {
                entries.count { NetworkLogFormat.filterOf(it.resourceType) == candidate }
            }
            // 該当が無い種別のチップは出さない。
            // ただし「すべて」と選択中のものは、選択を戻せなくなるため残す
            val isAlwaysVisible = candidate == NetworkLogUiState.ResourceFilter.All ||
                candidate == filter
            if (count == 0 && !isAlwaysVisible) return@mapNotNull null
            NetworkLogUiState.Filter(
                type = candidate,
                label = NetworkLogFormat.filterLabel(candidate),
                count = count,
                isSelected = candidate == filter,
            )
        }
    }

    private fun NetworkLogBody.toPreview(): NetworkLogUiState.Preview {
        return when (this) {
            is NetworkLogBody.Text -> {
                previewText = text
                NetworkLogUiState.Preview.Text(
                    text = text.take(MAX_PREVIEW_TEXT_LENGTH),
                    isTruncated = text.length > MAX_PREVIEW_TEXT_LENGTH,
                )
            }

            is NetworkLogBody.Binary -> {
                val bitmap = runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
                if (bitmap == null) {
                    NetworkLogUiState.Preview.Unavailable(
                        message = "この形式 (${mimeType.ifEmpty { "不明" }}) はプレビューできません",
                    )
                } else {
                    NetworkLogUiState.Preview.Image(
                        bitmap = bitmap.asImageBitmap(),
                        sizeLabel = "${bitmap.width} × ${bitmap.height} · " +
                            NetworkLogFormat.formatBytes(sizeBytes),
                    )
                }
            }

            is NetworkLogBody.Failure -> {
                val message = when (reason) {
                    NetworkLogBody.Failure.Reason.TooLarge ->
                        "サイズが大きいためプレビューできません (${NetworkLogFormat.formatBytes(sizeBytes)})"

                    NetworkLogBody.Failure.Reason.FetchFailed ->
                        "本文を取得できませんでした。キャッシュに残っていない可能性があります"

                    NetworkLogBody.Failure.Reason.NotReplayable ->
                        "GET 以外のリクエストは本文を再取得できません"

                    NetworkLogBody.Failure.Reason.Unavailable ->
                        "拡張機能へ接続できないため取得できませんでした"
                }
                NetworkLogUiState.Preview.Unavailable(message = message)
            }
        }
    }

    private fun NetworkLogEntry.toUiStateEntry(): NetworkLogUiState.Entry {
        return NetworkLogUiState.Entry(
            id = requestId,
            method = method,
            statusLabel = NetworkLogFormat.statusLabel(statusCode, error),
            statusKind = NetworkLogFormat.statusKind(statusCode, error),
            typeLabel = NetworkLogFormat.typeLabel(resourceType),
            name = NetworkLogFormat.displayName(url),
            host = NetworkLogFormat.hostOf(url),
            sizeLabel = NetworkLogFormat.formatBytes(sizeBytes),
            durationLabel = NetworkLogFormat.formatDuration(durationMillis),
            fromCache = fromCache,
        )
    }

    private fun NetworkLogEntry.toDetail(): NetworkLogUiState.Detail {
        return NetworkLogUiState.Detail(
            id = requestId,
            name = NetworkLogFormat.displayName(url),
            url = url,
            items = buildList {
                add(
                    NetworkLogUiState.Detail.Item(
                        label = "ステータス",
                        value = NetworkLogFormat.statusLabel(statusCode, error),
                    ),
                )
                add(NetworkLogUiState.Detail.Item(label = "メソッド", value = method))
                add(
                    NetworkLogUiState.Detail.Item(
                        label = "種別",
                        value = NetworkLogFormat.typeLabel(resourceType),
                    ),
                )
                add(
                    NetworkLogUiState.Detail.Item(
                        label = "MIME",
                        value = mimeTypeWithoutParameter.ifEmpty { "-" },
                    ),
                )
                add(
                    NetworkLogUiState.Detail.Item(
                        label = "サイズ",
                        value = NetworkLogFormat.formatBytes(sizeBytes),
                    ),
                )
                add(
                    NetworkLogUiState.Detail.Item(
                        label = "転送量",
                        value = NetworkLogFormat.formatBytes(transferredBytes),
                    ),
                )
                add(
                    NetworkLogUiState.Detail.Item(
                        label = "所要時間",
                        value = NetworkLogFormat.formatDuration(durationMillis),
                    ),
                )
                add(
                    NetworkLogUiState.Detail.Item(
                        label = "開始",
                        value = NetworkLogFormat.formatTime(startedAtMillis),
                    ),
                )
                add(
                    NetworkLogUiState.Detail.Item(
                        label = "キャッシュ",
                        value = if (fromCache) "あり" else "なし",
                    ),
                )
                error?.let { add(NetworkLogUiState.Detail.Item(label = "エラー", value = it)) }
            },
            requestHeaders = requestHeaders.map {
                NetworkLogUiState.Header(name = it.name, value = it.value)
            },
            responseHeaders = responseHeaders.map {
                NetworkLogUiState.Header(name = it.name, value = it.value)
            },
            preview = preview,
        )
    }

    private fun copyToClipboard(label: String, text: String, message: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MAX_PREVIEW_TEXT_LENGTH = 20000
    }
}
