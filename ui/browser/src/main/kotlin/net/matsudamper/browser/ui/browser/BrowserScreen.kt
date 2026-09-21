package net.matsudamper.browser.ui.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BrowserScreen(
    tabId: String,
    uiState: BrowserScreenUiState,
    canGoBackInPage: Boolean,
    previewHeaderContent: @Composable (modifier: Modifier, preview: TabPreviewContent, tabCount: Int?) -> Unit,
    browserTabContent: @Composable (
        modifier: Modifier,
        tabCount: Int?,
        onToolbarHorizontalDrag: (Float) -> Unit,
        onToolbarDragEnd: () -> Unit,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prevTab = uiState.swipePreview.previousTab
    val nextTab = uiState.swipePreview.nextTab
    val backToOpenerListener = uiState.swipePreview.backToOpenerListener

    val coroutineScope = rememberCoroutineScope()
    // URLバースワイプのオフセット（ピクセル単位）タブ切替時にリセット
    val swipeOffset = remember(tabId) { Animatable(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        val pageWidthPx = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        // タブ切替スワイプ閾値：割合と固定距離の短い方を使用（タブレット等の広い画面でも操作しやすくなる）
        val swipeThreshold = minOf(pageWidthPx * 0.3f, with(density) { 120.dp.toPx() })

        // リンクから開いたタブ（opener あり）で、まだページ内を遷移しておらず
        // (canGoBack=false)、前のタブが opener 本人である場合のみ予測型バックを有効化する。
        // この状態でのバックは「タブを閉じて opener へ戻る」ため、前のタブへスライドさせる。
        val backToOpenerEnabled = backToOpenerListener != null && !canGoBackInPage
        PredictiveBackHandler(enabled = backToOpenerEnabled) { progress ->
            try {
                progress.collect { backEvent ->
                    swipeOffset.snapTo(pageWidthPx * backEvent.progress)
                }
                swipeOffset.animateTo(pageWidthPx)
                backToOpenerListener?.onBackToOpener()
            } catch (e: CancellationException) {
                // キャンセル：元の位置へ戻す（handler のコルーチンは終了するため別スコープで実行）
                coroutineScope.launch { swipeOffset.animateTo(0f) }
                throw e
            }
        }

        prevTab?.let { preview ->
            TabPreviewPage(
                preview = preview.content,
                tabCount = uiState.groupTabCount,
                previewHeaderContent = previewHeaderContent,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset((swipeOffset.value - pageWidthPx).roundToInt(), 0) },
            )
        }

        nextTab?.let { preview ->
            TabPreviewPage(
                preview = preview.content,
                tabCount = uiState.groupTabCount,
                previewHeaderContent = previewHeaderContent,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset((swipeOffset.value + pageWidthPx).roundToInt(), 0) },
            )
        }

        browserTabContent(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) },
            uiState.groupTabCount,
            { delta ->
                coroutineScope.launch {
                    val maxOffset = if (prevTab != null) pageWidthPx else 0f
                    val minOffset = if (nextTab != null) -pageWidthPx else 0f
                    swipeOffset.snapTo(
                        (swipeOffset.value + delta).coerceIn(minOffset, maxOffset),
                    )
                }
            },
            {
                when {
                    swipeOffset.value > swipeThreshold && prevTab != null -> {
                        coroutineScope.launch {
                            swipeOffset.animateTo(pageWidthPx)
                            prevTab.listener.onSelect()
                        }
                    }

                    swipeOffset.value < -swipeThreshold && nextTab != null -> {
                        coroutineScope.launch {
                            swipeOffset.animateTo(-pageWidthPx)
                            nextTab.listener.onSelect()
                        }
                    }

                    else -> {
                        coroutineScope.launch {
                            swipeOffset.animateTo(0f)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun TabPreviewPage(
    preview: TabPreviewContent,
    tabCount: Int?,
    previewHeaderContent: @Composable (modifier: Modifier, preview: TabPreviewContent, tabCount: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 上部（ステータスバー）は BrowserToolBar の背景色で塗りつぶすため除外する
    Column(
        modifier = modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        ),
    ) {
        previewHeaderContent(Modifier.fillMaxWidth(), preview, tabCount)

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val previewImage = preview.previewImage
            // 別のタブへ切り替わったときに前のタブの画像を出さないよう、画像ごとに作り直す
            var decodedPreview: Bitmap? by remember(previewImage) { mutableStateOf(null) }
            LaunchedEffect(previewImage) {
                decodedPreview = if (previewImage != null && previewImage.isNotEmpty()) {
                    withContext(Dispatchers.Default) {
                        BitmapFactory.decodeByteArray(previewImage, 0, previewImage.size)
                    }
                } else {
                    null
                }
            }

            val bitmap = decodedPreview
            if (bitmap != null) {
                // 画像がコンテナより短い場合（フォルダブルで画面サイズが変わった場合）は上寄せ、
                // 同じサイズの場合はURLバーの高さ分のズレに対応するため下寄せ
                val scaledImageHeight = constraints.maxWidth.toFloat() / bitmap.width * bitmap.height
                val alignment = if (scaledImageHeight < constraints.maxHeight) {
                    Alignment.TopCenter
                } else {
                    Alignment.BottomCenter
                }
                Image(
                    modifier = Modifier.fillMaxSize(),
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    alignment = alignment,
                )
            } else {
                Text(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    text = preview.title.ifBlank { preview.currentUrl },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
