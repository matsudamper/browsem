package net.matsudamper.browser.ui.browser

import android.graphics.BitmapFactory
import android.util.Log
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
    onSelectTab: (String) -> Unit,
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

    val selectedTab = browserTabController.findTab(tabId)
    LaunchedEffect(tabId, homepageUrl, selectedTab) {
        // closeTab で閉じたタブは再作成しない。
        // NavDisplay の遷移アニメーション中に BrowserScreen が残っている間に
        // selectedTab=null で再コンポーズされてもホームページタブを作らないようにする。
        if (selectedTab == null && !browserTabController.wasTabClosed(tabId)) {
            browserTabController.getOrCreateTab(
                tabId = tabId,
                homepageUrl = homepageUrl,
            )
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

        // 前のタブのプレビュー画像（右スワイプ時に左から表示）
        prevTab?.let { tab ->
            TabPreviewPage(
                tab = tab,
                tabCount = uiState.groupTabCount,
                previewHeaderContent = previewHeaderContent,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset((swipeOffset.value - pageWidthPx).roundToInt(), 0) },
            )
        }

        // 次のタブのプレビュー画像（左スワイプ時に右から表示）
        nextTab?.let { tab ->
            TabPreviewPage(
                tab = tab,
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
                            onSelectTab(prevTab.tabId)
                        }
                    }

                    swipeOffset.value < -swipeThreshold && nextTab != null -> {
                        coroutineScope.launch {
                            swipeOffset.animateTo(-pageWidthPx)
                            onSelectTab(nextTab.tabId)
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

        Box(modifier = Modifier.fillMaxSize()) {
            val previewBitmap = tab.previewBitmap
            if (previewBitmap != null && previewBitmap.isNotEmpty()) {
                val bitmap = remember(previewBitmap) {
                    BitmapFactory.decodeByteArray(previewBitmap, 0, previewBitmap.size)
                }
                if (bitmap != null) {
                    Image(
                        modifier = Modifier.fillMaxSize(),
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        // URLバーの高さ分ズレるため、画像は下固定にする
                        alignment = Alignment.BottomCenter,
                    )
                    return
                }
            }

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
