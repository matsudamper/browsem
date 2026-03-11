package net.matsudamper.browser.screen.browser

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.NavKey
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.matsudamper.browser.BrowserSessionController
import net.matsudamper.browser.BrowserTab
import net.matsudamper.browser.GeckoBrowserTab
import net.matsudamper.browser.data.history.HistoryEntry
import net.matsudamper.browser.ThemeColorWebExtension
import net.matsudamper.browser.navigation.AppDestination
import net.matsudamper.browser.navigation.NavController
import org.mozilla.geckoview.GeckoResult

@Composable
internal fun BrowserScreen(
    key: AppDestination.Browser,
    homepageUrl: String,
    searchTemplate: String,
    backStack: MutableList<NavKey>,
    browserSessionController: BrowserSessionController,
    viewModel: BrowserScreenViewModel,
    navController: NavController,
    themeColorExtension: ThemeColorWebExtension,
    onInstallExtensionRequest: (String) -> Unit,
    handleNotificationPermission: (uri: String) -> GeckoResult<Int>,
    onSelectTab: (tabId: String, beforeTab: AppDestination.Browser?) -> Unit,
) {
    val currentSettings by viewModel.settingsUiState.collectAsState()
    val settingsUiState = currentSettings ?: return

    val selectedTab = remember(key.tabId) {
        val tab = browserSessionController.getOrCreateTab(
            tabId = key.tabId,
            homepageUrl = homepageUrl,
        )
        tab
    }
    val tabs = browserSessionController.tabs

    // 前後タブの取得
    val currentIndex = tabs.indexOfFirst { it.tabId == key.tabId }
    val prevTab = if (currentIndex > 0) tabs[currentIndex - 1] else null
    val nextTab = if (currentIndex >= 0 && currentIndex < tabs.lastIndex) tabs[currentIndex + 1] else null

    val coroutineScope = rememberCoroutineScope()
    // URLバースワイプのオフセット（ピクセル単位）タブ切替時にリセット
    val swipeOffset = remember(key.tabId) { Animatable(0f) }

    // 履歴サジェスト
    var historySuggestions by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var historySuggestionQuery by remember { mutableStateOf("") }

    LaunchedEffect(historySuggestionQuery) {
        viewModel.searchHistory(historySuggestionQuery).collectLatest { entries ->
            historySuggestions = entries
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        val pageWidthPx = constraints.maxWidth.toFloat()

        // 前のタブのプレビュー画像（右スワイプ時に左から表示）
        prevTab?.let { tab ->
            TabPreviewPage(
                tab = tab,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset((swipeOffset.value - pageWidthPx).roundToInt(), 0) },
            )
        }

        // 次のタブのプレビュー画像（左スワイプ時に右から表示）
        nextTab?.let { tab ->
            TabPreviewPage(
                tab = tab,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset((swipeOffset.value + pageWidthPx).roundToInt(), 0) },
            )
        }

        // 現在のタブのブラウザ（最前面）
        GeckoBrowserTab(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) },
            browserTab = selectedTab,
            homepageUrl = homepageUrl,
            searchTemplate = searchTemplate,
            translationProvider = settingsUiState.translationProvider,
            themeColorExtension = themeColorExtension,
            tabCount = tabs.size,
            onInstallExtensionRequest = onInstallExtensionRequest,
            onDesktopNotificationPermissionRequest = handleNotificationPermission,
            onOpenSettings = { backStack.add(AppDestination.Settings) },
            onOpenTabs = { backStack.add(AppDestination.Tabs) },
            browserSessionController = browserSessionController,
            onOpenNewSessionRequest = { uri ->
                val newTab = browserSessionController.createTabForNewSession(
                    initialUrl = uri,
                    openerTabId = key.tabId,
                )
                onSelectTab(newTab.tabId, key)
                newTab.session
            },
            onCloseTab = {
                val openerTabId = selectedTab.openerTabId
                browserSessionController.closeTab(key.tabId)
                val targetTabId = openerTabId?.takeIf { id ->
                    browserSessionController.tabs.any { it.tabId == id }
                } ?: browserSessionController.tabs.lastOrNull()?.tabId
                if (targetTabId != null) {
                    onSelectTab(targetTabId, null)
                }
            },
            onHistoryRecord = { url, title -> viewModel.recordHistory(url, title) },
            onHistoryTitleUpdate = { id, title -> viewModel.updateHistoryTitle(id, title) },
            historySuggestions = historySuggestions,
            onUrlInputChanged = { query ->
                historySuggestionQuery = query
            },
            onToolbarHorizontalDrag = { delta ->
                // URLバーの水平ドラッグをスワイプオフセットに反映
                coroutineScope.launch {
                    val maxOffset = if (prevTab != null) pageWidthPx else 0f
                    val minOffset = if (nextTab != null) -pageWidthPx else 0f
                    swipeOffset.snapTo(
                        (swipeOffset.value + delta).coerceIn(minOffset, maxOffset),
                    )
                }
            },
            onToolbarDragEnd = {
                // スワイプ完了時のタブ切替判定
                val threshold = pageWidthPx * 0.3f
                when {
                    swipeOffset.value > threshold && prevTab != null -> {
                        // 端までアニメーション完了後に前のタブへ切り替え
                        coroutineScope.launch {
                            swipeOffset.animateTo(pageWidthPx)
                            onSelectTab(prevTab.tabId, null)
                        }
                    }
                    swipeOffset.value < -threshold && nextTab != null -> {
                        // 端までアニメーション完了後に次のタブへ切り替え
                        coroutineScope.launch {
                            swipeOffset.animateTo(-pageWidthPx)
                            onSelectTab(nextTab.tabId, null)
                        }
                    }
                    else -> {
                        // しきい値未満の場合は元の位置へスナップバック
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
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.safeDrawingPadding()) {
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
    }
}
