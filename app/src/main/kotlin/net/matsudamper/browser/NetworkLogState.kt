package net.matsudamper.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.matsudamper.browser.feature.networklog.NetworkLogBody
import net.matsudamper.browser.feature.networklog.NetworkLogEntry
import net.matsudamper.browser.feature.networklog.NetworkLogStore
import net.matsudamper.browser.feature.networklog.NetworkLogWebExtension
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
    val coroutineScope = rememberCoroutineScope()
    val holder = remember(session) {
        NetworkLogStateHolder(
            extension = extension,
            store = store,
            context = context,
            coroutineScope = coroutineScope,
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
    val uiState = holder.createUiState(allEntries = entries, tabId = tabId)
    // 画像フィルタ選択中は一覧のサムネイルを読み込む。
    // 読み込み結果で entries 自体が変わるため、対象が変わったときだけ動くようキーを絞る
    val thumbnailKey = uiState.filters.firstOrNull { it.isSelected }?.type to
        uiState.entries.map { it.id }
    LaunchedEffect(holder, thumbnailKey) {
        holder.loadThumbnails()
    }
    return uiState
}

/**
 * ネットワークログ画面の状態保持。
 */
@Stable
internal class NetworkLogStateHolder(
    private val extension: NetworkLogWebExtension,
    private val store: NetworkLogStore,
    private val context: Context,
    private val coroutineScope: CoroutineScope,
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

    // 保存用に保持する取得済みの本文（画像プレビュー中のみ）
    private var previewBytes: ByteArray? = null
    private var previewMimeType: String = ""

    // 取得済みのサムネイル。取得に失敗した場合も再取得しないよう null を入れて記録する
    private val thumbnails = mutableStateMapOf<String, ThumbnailState>()

    // 取得済みサムネイルの追加順。上限を超えた分を古い方から捨てるために持つ
    private val thumbnailOrder = ArrayDeque<String>()

    // 一覧に表示しているログ。サムネイルの取得対象を可視範囲から求めるために保持する
    private var shownEntries: List<NetworkLogEntry> = emptyList()

    // サムネイルを出すかどうか（画像フィルタ選択中のみ true）
    private var showsThumbnail: Boolean = false

    // 一覧で見えている範囲。前後の余白を含めてサムネイルを先読みする
    private var visibleRange: IntRange = IntRange.EMPTY

    /** サムネイルの取得状態 */
    private class ThumbnailState(val bitmap: ImageBitmap?)

    // 表示対象のタブ ID。null の場合は全タブ分を表示する
    private var currentTabId: Int? = null

    private val callbacks = object : NetworkLogUiState.Callbacks {
        override fun onSearchQueryChange(query: String) {
            searchQuery = query
        }

        override fun onClickCloseDetail() {
            selectedId = null
            clearPreviewBody()
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
            clearPreviewBody()
            previewReloadCount += 1
        }

        override fun onClickSaveImage() {
            saveImage()
        }

        override fun onClickClear() {
            // タブを特定できていない間は全タブ分を表示しているため、
            // ここでクリアすると他のタブのログまで消えてしまう
            val tabId = currentTabId ?: return
            store.clear(tabId)
            selectedId = null
        }

        override fun onVisibleRangeChange(firstIndex: Int, lastIndex: Int) {
            val from = (firstIndex - THUMBNAIL_PREFETCH_MARGIN).coerceAtLeast(0)
            val to = lastIndex + THUMBNAIL_PREFETCH_MARGIN
            if (visibleRange.first == from && visibleRange.last == to) return
            visibleRange = from..to
            loadThumbnails()
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
        // サムネイルは画像フィルタを選んでいるときだけ出す
        val shown = filtered.asReversed()
        showsThumbnail = filter == NetworkLogUiState.ResourceFilter.Image
        shownEntries = shown
        return NetworkLogUiState(
            callbacks = callbacks,
            entries = shown.map { it.toUiStateEntry() },
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

    /**
     * 一覧で見えている範囲のサムネイルを取得する。
     * 一覧の先頭だけを対象にすると下までスクロールしたときに出ないため、
     * スクロール位置に追従して取得する。
     */
    fun loadThumbnails() {
        pruneThumbnails()
        if (!showsThumbnail) return
        val targets = shownEntries.filterIndexed { index, _ -> index in visibleRange }
        targets.forEach { entry ->
            // 取得済み・取得失敗済みのものは再取得しない
            if (thumbnails.containsKey(entry.requestId)) return@forEach
            extension.requestBody(
                requestId = entry.requestId,
                url = entry.url,
                method = entry.method,
            ) { body ->
                val bitmap = body.toThumbnailBitmap()
                putThumbnail(entry.requestId, ThumbnailState(bitmap = bitmap?.asImageBitmap()))
            }
        }
    }

    /** サムネイル用の小さい画像を作る。SVG はテキストとして届くため描画する */
    private fun NetworkLogBody.toThumbnailBitmap(): Bitmap? {
        return when (this) {
            is NetworkLogBody.Binary -> decodeThumbnail(bytes)
            is NetworkLogBody.Text -> {
                if (NetworkLogSvgRenderer.isSvg(mimeType)) {
                    NetworkLogSvgRenderer.render(text, THUMBNAIL_MAX_PIXELS)
                } else {
                    null
                }
            }

            is NetworkLogBody.Failure -> null
        }
    }

    /** サムネイルを記録する。上限を超えた分は古いものから捨てる */
    private fun putThumbnail(requestId: String, state: ThumbnailState) {
        if (thumbnails.put(requestId, state) == null) {
            thumbnailOrder.addLast(requestId)
        }
        while (thumbnailOrder.size > MAX_THUMBNAIL_CACHE) {
            val oldest = thumbnailOrder.removeFirst()
            thumbnails.remove(oldest)
        }
    }

    /** ログから消えたリクエストのサムネイルを捨てる */
    private fun pruneThumbnails() {
        if (thumbnails.isEmpty()) return
        val livingIds = store.entries.value.mapTo(mutableSetOf()) { it.requestId }
        if (thumbnails.keys.retainAll(livingIds)) {
            thumbnailOrder.retainAll(livingIds)
        }
    }

    /**
     * 一覧に並べる小さい画像を作る。
     * 元の解像度のまま持つとメモリを圧迫するため、縮小して読み込む。
     */
    private fun decodeThumbnail(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        val longerSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longerSide <= 0) return null
        var sampleSize = 1
        while (longerSide / sampleSize > THUMBNAIL_MAX_PIXELS) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
    }

    private fun clearPreviewBody() {
        previewText = null
        previewBytes = null
        previewMimeType = ""
    }

    /**
     * プレビュー中の画像を端末に保存する。
     * ラスタ画像はギャラリーから見られるよう Pictures へ、
     * ギャラリーで開けない SVG は Download へ保存する。
     */
    private fun saveImage() {
        val bytes = previewBytes ?: return
        val entry = selectedEntry() ?: return
        val mimeType = previewMimeType.substringBefore(';').trim()
            .ifEmpty { entry.mimeTypeWithoutParameter }
        val fileName = saveFileName(url = entry.url, mimeType = mimeType)
        val isSvg = NetworkLogSvgRenderer.isSvg(mimeType)
        coroutineScope.launch {
            val saved = withContext(Dispatchers.IO) {
                writeToMediaStore(
                    bytes = bytes,
                    fileName = fileName,
                    mimeType = mimeType.ifEmpty { "application/octet-stream" },
                    isSvg = isSvg,
                )
            }
            val directory = if (isSvg) "Download" else "Pictures"
            val message = if (saved) {
                "$directory に $fileName を保存しました"
            } else {
                "保存に失敗しました"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeToMediaStore(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        isSvg: Boolean,
    ): Boolean {
        val collection = if (isSvg) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (isSvg) Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_PICTURES,
            )
        }
        return runCatching {
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
            } ?: return false
            true
        }.getOrElse { error ->
            Log.w(TAG, "画像の保存に失敗", error)
            false
        }
    }

    /** 保存するファイル名。拡張子が無い場合は MIME から補う */
    private fun saveFileName(url: String, mimeType: String): String {
        val name = NetworkLogFormat.displayName(url).substringBefore('?').trim()
        val extension = SAVE_EXTENSIONS[mimeType]
        val fallbackName = "network_log_image"
        val baseName = name.takeIf { it.isNotEmpty() && it != "-" } ?: fallbackName
        return when {
            extension == null -> baseName
            baseName.endsWith(".$extension", ignoreCase = true) -> baseName
            else -> "$baseName.$extension"
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
                listener = object : NetworkLogUiState.Filter.Listener {
                    override fun onClick() {
                        this@NetworkLogStateHolder.filter = candidate
                    }
                },
            )
        }
    }

    private fun NetworkLogBody.toPreview(): NetworkLogUiState.Preview {
        return when (this) {
            is NetworkLogBody.Text -> {
                previewText = text
                // SVG はテキストとして届くため、画像として描画してから表示する
                val svgBitmap = if (NetworkLogSvgRenderer.isSvg(mimeType)) {
                    NetworkLogSvgRenderer.render(text, PREVIEW_IMAGE_MAX_PIXELS)
                } else {
                    null
                }
                if (svgBitmap != null) {
                    previewBytes = text.toByteArray()
                    previewMimeType = mimeType
                    NetworkLogUiState.Preview.Image(
                        bitmap = svgBitmap.asImageBitmap(),
                        sizeLabel = "SVG · ${NetworkLogFormat.formatBytes(sizeBytes)}",
                        canCopyBody = true,
                    )
                } else {
                    NetworkLogUiState.Preview.Text(
                        text = text.take(MAX_PREVIEW_TEXT_LENGTH),
                        isTruncated = text.length > MAX_PREVIEW_TEXT_LENGTH,
                    )
                }
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
                    previewBytes = bytes
                    previewMimeType = mimeType
                    NetworkLogUiState.Preview.Image(
                        bitmap = bitmap.asImageBitmap(),
                        sizeLabel = "${bitmap.width} × ${bitmap.height} · " +
                            NetworkLogFormat.formatBytes(sizeBytes),
                        canCopyBody = false,
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
        val requestId = requestId
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
            // 画像フィルタ中は枠を出し、その行が見えた時点で取得して埋める
            thumbnail = if (showsThumbnail) {
                NetworkLogUiState.Thumbnail(bitmap = thumbnails[requestId]?.bitmap)
            } else {
                null
            },
            listener = object : NetworkLogUiState.Entry.Listener {
                override fun onClick() {
                    selectedId = requestId
                    preview = NetworkLogUiState.Preview.Loading
                    clearPreviewBody()
                }
            },
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
            canSaveImage = preview is NetworkLogUiState.Preview.Image && previewBytes != null,
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

        // 詳細のプレビューで SVG を描画する大きさ（長辺のピクセル数）
        const val PREVIEW_IMAGE_MAX_PIXELS = 512

        // 保持するサムネイルの上限。スクロールで対象が増えても際限なく持たないようにする
        const val MAX_THUMBNAIL_CACHE = 120

        // 見えている範囲の前後に何件を先読みするか
        const val THUMBNAIL_PREFETCH_MARGIN = 5

        const val TAG = "NetworkLogState"

        // 保存時に補う拡張子
        val SAVE_EXTENSIONS = mapOf(
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/webp" to "webp",
            "image/gif" to "gif",
            "image/bmp" to "bmp",
            "image/svg+xml" to "svg",
            "image/avif" to "avif",
        )

        // サムネイルの長辺のピクセル数の目安
        const val THUMBNAIL_MAX_PIXELS = 128
    }
}
