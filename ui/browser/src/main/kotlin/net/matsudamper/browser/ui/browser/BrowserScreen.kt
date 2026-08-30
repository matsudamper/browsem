package net.matsudamper.browser.ui.browser

import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.matsudamper.browser.BrowserTab
import net.matsudamper.browser.BrowserTabController
import kotlin.math.roundToInt

@Composable
fun BrowserScreen(
    tabId: String,
    homepageUrl: String,
    uiState: BrowserScreenUiState,
    browserTabController: BrowserTabController,
    previewHeaderContent: @Composable (modifier: Modifier, tab: BrowserTab, tabCount: Int?) -> Unit,
    browserTabContent: @Composable (
        modifier: Modifier,
        selectedTab: BrowserTab,
        tabCount: Int?,
        onToolbarHorizontalDrag: (Float) -> Unit,
        onToolbarDragEnd: () -> Unit,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prevTab = uiState.swipePreview.previousTab
    val nextTab = uiState.swipePreview.nextTab
    val onBackToOpener = uiState.swipePreview.onBackToOpener

    val selectedTab = browserTabController.findTab(tabId)
    LaunchedEffect(tabId, homepageUrl, selectedTab) {
        // closeTab で閉じたタブは再作成しない。
        // NavDisplay の遷移アニメーション中に BrowserScreen が残っている間に
        // selectedTab=null で再コンポーズされてもホームページタブを作らないようにする。
        if (selectedTab == null && !browserTabController.wasTabClosed(tabId)) {
            // プロセス死後の savedInstanceState 復元時は BrowserScreen が compose される時点で
            // タブ復元がまだ完了していない。復元完了前に getOrCreateTab を呼ぶと、空の registry に
            // ホームページタブが sortOrder=0 で永続化されてしまう。
            // 復元完了を待ってから存在確認し、それでも存在しない場合のみ作成する。
            browserTabController.restoreComplete.await()
            if (browserTabController.findTab(tabId) == null && !browserTabController.wasTabClosed(tabId)) {
                browserTabController.getOrCreateTab(
                    tabId = tabId,
                    homepageUrl = homepageUrl,
                )
            }
        }
    }
    if (selectedTab == null) {
        /**
         * フォアグラウンド遷移直後に findTab が空を返すケースのフレーキー解析用ログ。
         * 該当時間帯に UrlBar が semantics tree から消えるテスト失敗との突き合わせに使う。
         */
        LaunchedEffect(tabId) {
            Log.d(
                "BrowserScreen",
                "selectedTab=null tabId=$tabId wasClosed=${browserTabController.wasTabClosed(tabId)}",
            )
        }
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

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
        val backToOpenerEnabled = onBackToOpener != null
        PredictiveBackHandler(enabled = backToOpenerEnabled) { progress ->
            try {
                progress.collect { backEvent ->
                    swipeOffset.snapTo(pageWidthPx * backEvent.progress)
                }
                swipeOffset.animateTo(pageWidthPx)
                onBackToOpener?.invoke()
            } catch (e: CancellationException) {
                // キャンセル：元の位置へ戻す（handler のコルーチンは終了するため別スコープで実行）
                coroutineScope.launch { swipeOffset.animateTo(0f) }
                throw e
            }
        }

        // 前のタブのプレビュー画像（右スワイプ時に左から表示）
        prevTab?.let { preview ->
            TabPreviewPage(
                tab = preview.tab,
                tabCount = uiState.groupTabCount,
                previewHeaderContent = previewHeaderContent,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset((swipeOffset.value - pageWidthPx).roundToInt(), 0) },
            )
        }

        // 次のタブのプレビュー画像（左スワイプ時に右から表示）
        nextTab?.let { preview ->
            TabPreviewPage(
                tab = preview.tab,
                tabCount = uiState.groupTabCount,
                previewHeaderContent = previewHeaderContent,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset((swipeOffset.value + pageWidthPx).roundToInt(), 0) },
            )
        }

        // 現在のタブのブラウザ（最前面）
        browserTabContent(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) },
            selectedTab,
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
                            prevTab.onSelect()
                        }
                    }

                    swipeOffset.value < -swipeThreshold && nextTab != null -> {
                        coroutineScope.launch {
                            swipeOffset.animateTo(-pageWidthPx)
                            nextTab.onSelect()
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
    tab: BrowserTab,
    tabCount: Int?,
    previewHeaderContent: @Composable (modifier: Modifier, tab: BrowserTab, tabCount: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 上部（ステータスバー）は BrowserToolBar の背景色で塗りつぶすため除外する
    Column(
        modifier = modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        ),
    ) {
        previewHeaderContent(Modifier.fillMaxWidth(), tab, tabCount)

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val previewBitmap = tab.previewBitmap
            val bitmap = if (previewBitmap != null && previewBitmap.isNotEmpty()) {
                remember(previewBitmap) {
                    BitmapFactory.decodeByteArray(previewBitmap, 0, previewBitmap.size)
                }
            } else {
                null
            }

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
                    text = tab.title.ifBlank { tab.currentUrl },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
