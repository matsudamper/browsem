package net.matsudamper.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.distinctUntilChanged
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.ui.common.BrowserTheme
import net.matsudamper.browser.ui.common.ThemeSurfaceStatusBarAppearanceEffect
import net.matsudamper.browser.resources.R as ResourcesR

/**
 * ネットワークログを全画面ダイアログで表示する。
 * ブラウザのタブ上に重ねて出すため、タブ内の状態を保ったまま開閉できる。
 */
@Composable
internal fun NetworkLogDialog(uiState: NetworkLogUiState) {
    Dialog(
        // 詳細を開いている場合、OS の戻る操作では画面ごと閉じずに一覧へ戻す
        onDismissRequest = {
            if (uiState.detail != null) {
                uiState.callbacks.onClickCloseDetail()
            } else {
                uiState.callbacks.onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            ThemeSurfaceStatusBarAppearanceEffect()
            NetworkLogScreen(uiState = uiState)
        }
    }
}

/**
 * ネットワークログ画面。
 * 一覧と詳細を 1 画面で切り替える。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NetworkLogScreen(
    uiState: NetworkLogUiState,
    modifier: Modifier = Modifier,
) {
    val detail = uiState.detail
    // 詳細を開いている間も保持し、一覧へ戻ったときにスクロール位置を復元する
    val listState = rememberLazyListState()
    Scaffold(
        modifier = modifier.testTag(NetworkLogScreenTestTags.Screen.testTag),
        topBar = {
            if (detail == null) {
                TopAppBar(
                    title = { Text("ネットワークログ") },
                    navigationIcon = {
                        IconButton(onClick = uiState.callbacks::onDismiss) {
                            Icon(
                                painter = painterResource(ResourcesR.drawable.close_24dp),
                                contentDescription = "閉じる",
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            modifier = Modifier.testTag(NetworkLogScreenTestTags.ClearButton.testTag),
                            enabled = uiState.canClear,
                            onClick = uiState.callbacks::onClickClear,
                        ) {
                            Icon(
                                painter = painterResource(ResourcesR.drawable.ic_delete_24dp),
                                contentDescription = "ログを消去",
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Text(text = detail.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(
                            modifier = Modifier.testTag(NetworkLogScreenTestTags.DetailBackButton.testTag),
                            onClick = uiState.callbacks::onClickCloseDetail,
                        ) {
                            Icon(
                                painter = painterResource(ResourcesR.drawable.ic_arrow_back_24dp),
                                contentDescription = "一覧へ戻る",
                            )
                        }
                    },
                    actions = {
                        if (detail.canSaveImage) {
                            IconButton(
                                modifier = Modifier
                                    .testTag(NetworkLogScreenTestTags.SaveImageButton.testTag),
                                onClick = uiState.callbacks::onClickSaveImage,
                            ) {
                                Icon(
                                    painter = painterResource(ResourcesR.drawable.ic_download_24dp),
                                    contentDescription = "画像を保存",
                                )
                            }
                        }
                        IconButton(onClick = uiState.callbacks::onClickCopyUrl) {
                            Icon(
                                painter = painterResource(ResourcesR.drawable.ic_content_copy_24dp),
                                contentDescription = "URL をコピー",
                            )
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            if (detail == null) {
                NetworkLogList(uiState = uiState, listState = listState)
            } else {
                NetworkLogDetail(detail = detail, callbacks = uiState.callbacks)
            }
        }
    }
}

@Composable
private fun NetworkLogList(
    uiState: NetworkLogUiState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val callbacks = uiState.callbacks
    // 見えている範囲を伝えて、その範囲のサムネイルだけ取得させる
    LaunchedEffect(listState, callbacks) {
        snapshotFlow {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                null
            } else {
                visibleItems.first().index to visibleItems.last().index
            }
        }
            .distinctUntilChanged()
            .collect { range ->
                if (range == null) return@collect
                callbacks.onVisibleRangeChange(
                    firstIndex = range.first,
                    lastIndex = range.second,
                )
            }
    }
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag(NetworkLogScreenTestTags.SearchField.testTag),
            value = uiState.searchQuery,
            onValueChange = uiState.callbacks::onSearchQueryChange,
            label = { Text("URL で絞り込み") },
            singleLine = true,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(uiState.filters, key = { it.type }) { filter ->
                FilterChip(
                    selected = filter.isSelected,
                    onClick = { uiState.callbacks.onClickFilter(filter.type) },
                    label = { Text("${filter.label} ${filter.count}") },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = uiState.summary.countLabel,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = uiState.summary.sizeLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        uiState.notice?.let { notice ->
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                text = notice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        if (uiState.entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    modifier = Modifier.padding(32.dp),
                    text = "通信ログがありません。\nページを再読み込みすると記録されます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
            ) {
                items(uiState.entries, key = { it.id }) { entry ->
                    NetworkLogRow(
                        entry = entry,
                        onClick = { uiState.callbacks.onClickEntry(entry.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun NetworkLogRow(
    entry: NetworkLogUiState.Entry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(NetworkLogScreenTestTags.Entry.testTag)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(statusLabel = entry.statusLabel, statusKind = entry.statusKind)
            entry.thumbnail?.let { thumbnail ->
                NetworkLogThumbnail(thumbnail = thumbnail)
            }
            Text(
                modifier = Modifier.weight(1f),
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.sizeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${entry.method} · ${entry.typeLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                modifier = Modifier.weight(1f),
                text = entry.host,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entry.fromCache) "キャッシュ" else entry.durationLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一覧に出す画像のサムネイル。
 * 取得できていない間もレイアウトが動かないよう、同じ大きさの枠を出す。
 */
@Composable
private fun NetworkLogThumbnail(
    thumbnail: NetworkLogUiState.Thumbnail,
    modifier: Modifier = Modifier,
) {
    val boxModifier = modifier
        .size(THUMBNAIL_SIZE)
        .background(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(4.dp),
        )
        .testTag(NetworkLogScreenTestTags.Thumbnail.testTag)
    if (thumbnail.bitmap == null) {
        Box(modifier = boxModifier)
    } else {
        Image(
            modifier = boxModifier,
            bitmap = thumbnail.bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun StatusBadge(
    statusLabel: String,
    statusKind: NetworkLogUiState.StatusKind,
    modifier: Modifier = Modifier,
) {
    val color = when (statusKind) {
        NetworkLogUiState.StatusKind.Success -> MaterialTheme.colorScheme.primary
        NetworkLogUiState.StatusKind.Redirect -> MaterialTheme.colorScheme.tertiary
        NetworkLogUiState.StatusKind.ClientError,
        NetworkLogUiState.StatusKind.ServerError,
        NetworkLogUiState.StatusKind.Failed,
        -> MaterialTheme.colorScheme.error

        NetworkLogUiState.StatusKind.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        modifier = modifier
            .background(
                color = color.copy(alpha = BADGE_BACKGROUND_ALPHA),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .width(36.dp),
        text = statusLabel,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

@Composable
private fun NetworkLogDetail(
    detail: NetworkLogUiState.Detail,
    callbacks: NetworkLogUiState.Callbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .testTag(NetworkLogScreenTestTags.Detail.testTag),
    ) {
        SelectionContainer {
            Text(
                modifier = Modifier.padding(vertical = 8.dp),
                text = detail.url,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        detail.items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    modifier = Modifier.width(96.dp),
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    modifier = Modifier.weight(1f),
                    text = item.value,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        SectionTitle(title = "プレビュー")
        NetworkLogPreview(preview = detail.preview, callbacks = callbacks)
        HeaderSection(title = "レスポンスヘッダ", headers = detail.responseHeaders)
        HeaderSection(title = "リクエストヘッダ", headers = detail.requestHeaders)
    }
}

@Composable
private fun NetworkLogPreview(
    preview: NetworkLogUiState.Preview,
    callbacks: NetworkLogUiState.Callbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when (preview) {
            is NetworkLogUiState.Preview.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }

            is NetworkLogUiState.Preview.Image -> {
                Image(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                        .testTag(NetworkLogScreenTestTags.ImagePreview.testTag),
                    bitmap = preview.bitmap,
                    contentDescription = "レスポンスのプレビュー",
                    contentScale = ContentScale.Fit,
                )
                Text(
                    modifier = Modifier.padding(top = 4.dp),
                    text = preview.sizeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (preview.canCopyBody) {
                    TextButton(onClick = callbacks::onClickCopyBody) {
                        Text("本文をコピー")
                    }
                }
            }

            is NetworkLogUiState.Preview.Text -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                        )
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(
                        modifier = Modifier.testTag(NetworkLogScreenTestTags.TextPreview.testTag),
                        text = preview.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (preview.isTruncated) {
                    Text(
                        text = "長いため一部のみ表示しています",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = callbacks::onClickCopyBody) {
                    Text("本文をコピー")
                }
            }

            is NetworkLogUiState.Preview.Unavailable -> {
                Text(
                    modifier = Modifier.padding(vertical = 8.dp),
                    text = preview.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (preview !is NetworkLogUiState.Preview.Loading) {
            TextButton(onClick = callbacks::onClickReloadPreview) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        modifier = Modifier.size(16.dp),
                        painter = painterResource(ResourcesR.drawable.ic_refresh_24dp),
                        contentDescription = null,
                    )
                    Text("プレビューを再取得")
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(
    title: String,
    headers: List<NetworkLogUiState.Header>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(title = title)
        if (headers.isEmpty()) {
            Text(
                text = "なし",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        headers.forEach { header ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    modifier = Modifier.width(120.dp),
                    text = header.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    modifier = Modifier.weight(1f),
                    text = header.value,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
        text = title,
        style = MaterialTheme.typography.titleSmall,
    )
}

private const val BADGE_BACKGROUND_ALPHA = 0.15f
private val THUMBNAIL_SIZE = 32.dp

sealed interface NetworkLogScreenTestTags {
    val id: String
    val testTag get() = "${NetworkLogScreenTestTags::class.java.name}#$id"

    object Screen : NetworkLogScreenTestTags { override val id = "screen" }
    object SearchField : NetworkLogScreenTestTags { override val id = "search_field" }
    object ClearButton : NetworkLogScreenTestTags { override val id = "clear_button" }
    object Entry : NetworkLogScreenTestTags { override val id = "entry" }
    object Detail : NetworkLogScreenTestTags { override val id = "detail" }
    object DetailBackButton : NetworkLogScreenTestTags { override val id = "detail_back_button" }
    object ImagePreview : NetworkLogScreenTestTags { override val id = "image_preview" }
    object Thumbnail : NetworkLogScreenTestTags { override val id = "thumbnail" }
    object SaveImageButton : NetworkLogScreenTestTags { override val id = "save_image_button" }
    object TextPreview : NetworkLogScreenTestTags { override val id = "text_preview" }
}

private object PreviewNetworkLogCallbacks : NetworkLogUiState.Callbacks {
    override fun onClickFilter(filter: NetworkLogUiState.ResourceFilter) = Unit
    override fun onSearchQueryChange(query: String) = Unit
    override fun onClickEntry(id: String) = Unit
    override fun onClickCloseDetail() = Unit
    override fun onClickCopyUrl() = Unit
    override fun onClickCopyBody() = Unit
    override fun onClickReloadPreview() = Unit
    override fun onClickSaveImage() = Unit
    override fun onClickClear() = Unit
    override fun onVisibleRangeChange(firstIndex: Int, lastIndex: Int) = Unit
    override fun onDismiss() = Unit
}

private fun previewEntries(): List<NetworkLogUiState.Entry> {
    return listOf(
        NetworkLogUiState.Entry(
            id = "1",
            method = "GET",
            statusLabel = "200",
            statusKind = NetworkLogUiState.StatusKind.Success,
            typeLabel = "文書",
            name = "index.html",
            host = "example.com",
            sizeLabel = "12.4 KB",
            durationLabel = "231 ms",
            fromCache = false,
            thumbnail = null,
        ),
        NetworkLogUiState.Entry(
            id = "2",
            method = "GET",
            statusLabel = "200",
            statusKind = NetworkLogUiState.StatusKind.Success,
            typeLabel = "JS",
            name = "main.bundle.js",
            host = "cdn.example.com",
            sizeLabel = "482.0 KB",
            durationLabel = "1.24 s",
            fromCache = false,
            thumbnail = null,
        ),
        NetworkLogUiState.Entry(
            id = "3",
            method = "GET",
            statusLabel = "200",
            statusKind = NetworkLogUiState.StatusKind.Success,
            typeLabel = "画像",
            name = "hero@2x.png",
            host = "img.example.com",
            sizeLabel = "1.2 MB",
            durationLabel = "88 ms",
            fromCache = true,
            thumbnail = null,
        ),
        NetworkLogUiState.Entry(
            id = "4",
            method = "POST",
            statusLabel = "404",
            statusKind = NetworkLogUiState.StatusKind.ClientError,
            typeLabel = "XHR",
            name = "search",
            host = "api.example.com",
            sizeLabel = "512 B",
            durationLabel = "64 ms",
            fromCache = false,
            thumbnail = null,
        ),
        NetworkLogUiState.Entry(
            id = "5",
            method = "GET",
            statusLabel = "失敗",
            statusKind = NetworkLogUiState.StatusKind.Failed,
            typeLabel = "その他",
            name = "beacon.gif",
            host = "tracker.example.net",
            sizeLabel = "-",
            durationLabel = "12 ms",
            fromCache = false,
            thumbnail = null,
        ),
    )
}

private fun previewFilters(): List<NetworkLogUiState.Filter> {
    return listOf(
        NetworkLogUiState.Filter(NetworkLogUiState.ResourceFilter.All, "すべて", 5, true),
        NetworkLogUiState.Filter(NetworkLogUiState.ResourceFilter.Document, "文書", 1, false),
        NetworkLogUiState.Filter(NetworkLogUiState.ResourceFilter.Script, "JS", 1, false),
        NetworkLogUiState.Filter(NetworkLogUiState.ResourceFilter.Image, "画像", 1, false),
        NetworkLogUiState.Filter(NetworkLogUiState.ResourceFilter.Xhr, "XHR", 1, false),
    )
}

private fun previewDetail(preview: NetworkLogUiState.Preview): NetworkLogUiState.Detail {
    return NetworkLogUiState.Detail(
        id = "3",
        name = "hero@2x.png",
        url = "https://img.example.com/assets/images/hero@2x.png?v=20260818",
        items = listOf(
            NetworkLogUiState.Detail.Item("ステータス", "200"),
            NetworkLogUiState.Detail.Item("メソッド", "GET"),
            NetworkLogUiState.Detail.Item("種別", "画像"),
            NetworkLogUiState.Detail.Item("MIME", "image/png"),
            NetworkLogUiState.Detail.Item("サイズ", "1.2 MB"),
            NetworkLogUiState.Detail.Item("転送量", "0 B"),
            NetworkLogUiState.Detail.Item("所要時間", "88 ms"),
            NetworkLogUiState.Detail.Item("開始", "12:34:56.789"),
            NetworkLogUiState.Detail.Item("キャッシュ", "あり"),
        ),
        canSaveImage = preview is NetworkLogUiState.Preview.Image,
        requestHeaders = listOf(
            NetworkLogUiState.Header("Accept", "image/avif,image/webp,*/*"),
            NetworkLogUiState.Header("Referer", "https://example.com/"),
        ),
        responseHeaders = listOf(
            NetworkLogUiState.Header("content-type", "image/png"),
            NetworkLogUiState.Header("content-length", "1258291"),
            NetworkLogUiState.Header("cache-control", "public, max-age=31536000"),
        ),
        preview = preview,
    )
}

/** プレビュー用のダミー画像 */
private fun previewImageBitmap(): ImageBitmap {
    val bitmap = ImageBitmap(width = PREVIEW_IMAGE_WIDTH, height = PREVIEW_IMAGE_HEIGHT)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { color = Color(0xFF3F51B5) }
    canvas.drawRect(0f, 0f, PREVIEW_IMAGE_WIDTH.toFloat(), PREVIEW_IMAGE_HEIGHT.toFloat(), paint)
    paint.color = Color(0xFFFFC107)
    canvas.drawCircle(
        center = Offset(PREVIEW_IMAGE_WIDTH / 2f, PREVIEW_IMAGE_HEIGHT / 2f),
        radius = PREVIEW_IMAGE_HEIGHT / 3f,
        paint = paint,
    )
    return bitmap
}

private const val PREVIEW_IMAGE_WIDTH = 240
private const val PREVIEW_IMAGE_HEIGHT = 135

@Preview(name = "一覧")
@Composable
private fun PreviewNetworkLogScreenList() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        NetworkLogScreen(
            uiState = NetworkLogUiState(
                callbacks = PreviewNetworkLogCallbacks,
                entries = previewEntries(),
                filters = previewFilters(),
                searchQuery = "",
                summary = NetworkLogUiState.Summary(countLabel = "5 件", sizeLabel = "1.7 MB"),
                notice = null,
                canClear = true,
                detail = null,
            ),
        )
    }
}

/** 画像フィルタ選択時の一覧。サムネイル取得前の項目も混ぜている */
private fun previewImageEntries(): List<NetworkLogUiState.Entry> {
    return listOf(
        NetworkLogUiState.Entry(
            id = "3",
            method = "GET",
            statusLabel = "200",
            statusKind = NetworkLogUiState.StatusKind.Success,
            typeLabel = "画像",
            name = "hero@2x.png",
            host = "img.example.com",
            sizeLabel = "1.2 MB",
            durationLabel = "88 ms",
            fromCache = true,
            thumbnail = NetworkLogUiState.Thumbnail(bitmap = previewImageBitmap()),
        ),
        NetworkLogUiState.Entry(
            id = "6",
            method = "GET",
            statusLabel = "200",
            statusKind = NetworkLogUiState.StatusKind.Success,
            typeLabel = "画像",
            name = "avatar.webp",
            host = "img.example.com",
            sizeLabel = "24.5 KB",
            durationLabel = "31 ms",
            fromCache = false,
            thumbnail = NetworkLogUiState.Thumbnail(bitmap = previewImageBitmap()),
        ),
        NetworkLogUiState.Entry(
            id = "7",
            method = "GET",
            statusLabel = "200",
            statusKind = NetworkLogUiState.StatusKind.Success,
            typeLabel = "画像",
            name = "sprite.svg",
            host = "cdn.example.com",
            sizeLabel = "8.0 KB",
            durationLabel = "18 ms",
            fromCache = false,
            thumbnail = NetworkLogUiState.Thumbnail(bitmap = null),
        ),
    )
}

@Preview(name = "一覧_画像フィルタ")
@Composable
private fun PreviewNetworkLogScreenImageFilter() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        NetworkLogScreen(
            uiState = NetworkLogUiState(
                callbacks = PreviewNetworkLogCallbacks,
                entries = previewImageEntries(),
                filters = listOf(
                    NetworkLogUiState.Filter(NetworkLogUiState.ResourceFilter.All, "すべて", 8, false),
                    NetworkLogUiState.Filter(NetworkLogUiState.ResourceFilter.Image, "画像", 3, true),
                    NetworkLogUiState.Filter(NetworkLogUiState.ResourceFilter.Script, "JS", 1, false),
                ),
                searchQuery = "",
                summary = NetworkLogUiState.Summary(countLabel = "3 件", sizeLabel = "1.2 MB"),
                notice = null,
                canClear = true,
                detail = null,
            ),
        )
    }
}

@Preview(name = "一覧が空")
@Composable
private fun PreviewNetworkLogScreenEmpty() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        NetworkLogScreen(
            uiState = NetworkLogUiState(
                callbacks = PreviewNetworkLogCallbacks,
                entries = emptyList(),
                filters = listOf(previewFilters().first()),
                searchQuery = "",
                summary = NetworkLogUiState.Summary(countLabel = "0 件", sizeLabel = "0 B"),
                notice = null,
                canClear = true,
                detail = null,
            ),
        )
    }
}

@Preview(name = "詳細_画像プレビュー")
@Composable
private fun PreviewNetworkLogScreenImageDetail() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        NetworkLogScreen(
            uiState = NetworkLogUiState(
                callbacks = PreviewNetworkLogCallbacks,
                entries = previewEntries(),
                filters = previewFilters(),
                searchQuery = "",
                summary = NetworkLogUiState.Summary(countLabel = "5 件", sizeLabel = "1.7 MB"),
                notice = null,
                canClear = true,
                detail = previewDetail(
                    NetworkLogUiState.Preview.Image(
                        bitmap = previewImageBitmap(),
                        sizeLabel = "240 × 135 · 1.2 MB",
                        canCopyBody = false,
                    ),
                ),
            ),
        )
    }
}

@Preview(name = "詳細_SVGプレビュー")
@Composable
private fun PreviewNetworkLogScreenSvgDetail() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        NetworkLogScreen(
            uiState = NetworkLogUiState(
                callbacks = PreviewNetworkLogCallbacks,
                entries = previewImageEntries(),
                filters = previewFilters(),
                searchQuery = "",
                summary = NetworkLogUiState.Summary(countLabel = "3 件", sizeLabel = "1.2 MB"),
                notice = null,
                canClear = true,
                detail = previewDetail(
                    NetworkLogUiState.Preview.Image(
                        bitmap = previewImageBitmap(),
                        sizeLabel = "SVG · 8.0 KB",
                        canCopyBody = true,
                    ),
                ),
            ),
        )
    }
}

@Preview(name = "詳細_テキストプレビュー")
@Composable
private fun PreviewNetworkLogScreenTextDetail() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        NetworkLogScreen(
            uiState = NetworkLogUiState(
                callbacks = PreviewNetworkLogCallbacks,
                entries = previewEntries(),
                filters = previewFilters(),
                searchQuery = "main",
                summary = NetworkLogUiState.Summary(countLabel = "1 件", sizeLabel = "482.0 KB"),
                notice = null,
                canClear = true,
                detail = previewDetail(
                    NetworkLogUiState.Preview.Text(
                        text = """
                            (function () {
                              "use strict";
                              const app = document.querySelector("#app");
                              app.textContent = "hello";
                            })();
                        """.trimIndent(),
                        isTruncated = true,
                    ),
                ),
            ),
        )
    }
}
