package net.matsudamper.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.ui.browser.UrlBarSuggestionsUiState
import net.matsudamper.browser.ui.common.BrowserTheme
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * GeckoView が内部で登録するキーボード高さ通知用の WindowInsets リスナーのキー。
 */
private const val GECKO_KEYBOARD_INSETS_LISTENER_KEY = "KEYBOARD_WINDOW_INSETS_LISTENER"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrowserContentHost(
    state: BrowserTabScreenState,
    id: Int,
    session: GeckoSession,
    browserTab: BrowserTab,
    latestOnRefresh: () -> Unit,
    updateGeckoView: (GeckoView) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Firefox (Fenix) の ImeInsetsSynchronizer と同じく、キーボード分だけ表示領域を
    // 物理的に縮める。Gecko は onKeyboardHeight を受け取っても visual viewport を
    // 縮めないため、縮めない限り position: fixed の下部入力欄も文書末尾の入力欄も
    // キーボードの上へ出せない。
    //
    // ime ではなく imeAnimationTarget を使う。ime はアニメーションの各フレームで
    // 変化するため、追従すると 1 回の表示で何度もリサイズが走る。確定値なら
    // 表示/非表示ごとに 1 回で済む。
    //
    // 親が既に消費した分は差し引く。window.open のオーバーレイでは
    // safeDrawing (IME 含む) が消費済みで、表示領域は既に縮んでいる。
    // Custom Tab / WebApp も edge-to-edge でウィンドウが縮まないため手動縮小を使う
    //（IME 中のナビバー padding は GeckoBrowserTab 側で外す）。
    val density = LocalDensity.current
    var consumedBottomPx by remember { mutableIntStateOf(0) }
    val imeTargetBottomPx = WindowInsets.imeAnimationTarget.getBottom(density)
    val keyboardHeightPx = (imeTargetBottomPx - consumedBottomPx).coerceAtLeast(0)

    Box(
        modifier = modifier.onConsumedWindowInsetsChanged { consumed ->
            consumedBottomPx = consumed.getBottom(density)
        },
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            factory = { context ->
                GeckoSwipeRefreshLayout(context).also { swipeRefreshLayout ->
                    var swipeRefreshScrollEnabled = false
                    // ピンチ等のマルチタッチジェスチャー中は PTR を発動させないための
                    // ジェスチャー単位のフラグ。ACTION_DOWN の非同期判定結果が
                    // ACTION_POINTER_DOWN より後に届いても上書きされないようにする。
                    var gestureHadMultiTouch = false
                    // 長押しメニューの誤発火判定用に、ジェスチャー開始位置とタッチスロップを保持する
                    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                    var gestureDownX = 0f
                    var gestureDownY = 0f
                    val gecko = GeckoView(context).also { geckoView ->
                        geckoView.id = id
                        geckoView.isNestedScrollingEnabled = true
                        geckoView.setAutofillEnabled(true)
                        geckoView.importantForAutofill =
                            View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS
                        geckoView.setSession(session)
                        // Gecko 内部のキーボード高さ通知を解除する。表示領域は
                        // padding で物理的に縮めており、Gecko 側でも適用されると
                        // レイアウトが縮んだ表示領域に追従しない。
                        // GeckoView.onAttachedToWindow が毎回張り直すため、
                        // attach のたびに外す。
                        geckoView.addOnAttachStateChangeListener(
                            object : View.OnAttachStateChangeListener {
                                override fun onViewAttachedToWindow(v: View) {
                                    (v as GeckoView)
                                        .removeWindowInsetsListener(GECKO_KEYBOARD_INSETS_LISTENER_KEY)
                                }

                                override fun onViewDetachedFromWindow(v: View) = Unit
                            },
                        )
                        // Engine 側で非アクティブ扱いになると Compositor の描画更新が止まり、
                        // 復帰時の黒画面につながるため、初期生成時に必ず active 化する。
                        session.setActive(true)
                        // キーボード操作 (コンテキストメニューキー等) 由来のメニューを
                        // 過去のタッチ記録で抑制しないよう、キー入力でタッチ記録を解除する。
                        geckoView.setOnKeyListener { _, _, _ ->
                            state.onContentNonTouchInput()
                            false
                        }
                        @SuppressLint("ClickableViewAccessibility")
                        geckoView.setOnTouchListener { view, event ->
                            // マウスの右クリック等はタッチジェスチャーとして扱わない
                            if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
                                state.onContentNonTouchInput()
                                return@setOnTouchListener false
                            }
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    swipeRefreshScrollEnabled = false
                                    gestureHadMultiTouch = false
                                    gestureDownX = event.x
                                    gestureDownY = event.y
                                    state.onContentTouchStart()
                                    (view as GeckoView).onTouchEventForDetailResult(event).then { detail ->
                                        if (detail != null && !gestureHadMultiTouch) {
                                            swipeRefreshScrollEnabled = canTriggerPullToRefresh(
                                                handledResult = detail.handledResult(),
                                                scrollableDirections = detail.scrollableDirections(),
                                                overscrollDirections = detail.overscrollDirections(),
                                            )
                                        }
                                        GeckoResult.fromValue<Void>(null)
                                    }
                                    true
                                }

                                MotionEvent.ACTION_POINTER_DOWN -> {
                                    gestureHadMultiTouch = true
                                    swipeRefreshScrollEnabled = false
                                    // ピンチ等のマルチタッチは長押しではない
                                    state.onContentTouchMoved()
                                    false
                                }

                                MotionEvent.ACTION_MOVE -> {
                                    val movedBeyondSlop =
                                        abs(event.x - gestureDownX) > touchSlop ||
                                            abs(event.y - gestureDownY) > touchSlop
                                    if (movedBeyondSlop) {
                                        state.onContentTouchMoved()
                                    }
                                    false
                                }

                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    state.onContentTouchEnd()
                                    false
                                }

                                else -> false
                            }
                        }
                    }
                    updateGeckoView(gecko)
                    swipeRefreshLayout.addView(
                        gecko,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
                        !swipeRefreshScrollEnabled ||
                            state.scrollY > 0 ||
                            state.visualViewportScale > 1.05f
                    }
                    swipeRefreshLayout.setOnRefreshListener {
                        state.isRefreshing = true
                        latestOnRefresh()
                    }
                }
            },
            update = { swipeRefreshLayout ->
                swipeRefreshLayout.isEnabled = !state.isFullScreen
                swipeRefreshLayout.isRefreshing = state.isRefreshing
                // 実際の padding への反映と高さの上限制御は onMeasure で行う。
                swipeRefreshLayout.keyboardHeight = keyboardHeightPx
                val geckoView = swipeRefreshLayout.findViewById<GeckoView>(id)
                if (!state.isUrlInputFocused && !state.showFindInPage && !geckoView.isFocused) {
                    geckoView.requestFocus()
                }
            },
        )

        val previewBytes = browserTab.previewBitmap
        var previewBitmap: Bitmap? by remember(null) {
            mutableStateOf(null)
        }
        LaunchedEffect(previewBytes) {
            previewBitmap = if (previewBytes != null) {
                BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size)
            } else {
                null
            }
        }
        if (!state.renderReady) {
            val bitmap = previewBitmap
            if (bitmap != null) {
                Image(
                    modifier = Modifier.fillMaxSize(),
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    alignment = Alignment.TopStart,
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
internal fun BrowserTabOverlayLayer(
    state: BrowserTabScreenState,
    urlBarSuggestions: UrlBarSuggestionsUiState,
    onHistorySuggestionClick: (net.matsudamper.browser.data.history.HistoryEntry) -> Unit,
    onWebSuggestionClick: (String) -> Unit,
    clipboardUrl: String?,
    onClipboardUrlClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        state.pageLoadError?.let { pageLoadError ->
            PageLoadErrorOverlay(
                pageLoadError = pageLoadError,
                onRetry = state::retryPageLoad,
            )
        }

        if (
            shouldShowUrlSuggestions(
                showFindInPage = state.showFindInPage,
                isUrlInputFocused = state.isUrlInputFocused,
                suggestionCount = urlBarSuggestions.historySuggestions.size +
                    urlBarSuggestions.webSuggestions.size +
                    if (urlBarSuggestions.isLoadingWebSuggestions) {
                        1
                    } else {
                        0 +
                            if (clipboardUrl != null) 1 else 0
                    },
                currentPageUrl = state.currentPageUrl,
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(BrowserTabSurfaceTestTags.UrlSuggestionList.testTag),
                color = MaterialTheme.colorScheme.surface,
            ) {
                UrlSuggestionList(
                    currentPageUrl = state.currentPageUrl,
                    historySuggestions = urlBarSuggestions.historySuggestions,
                    webSuggestions = urlBarSuggestions.webSuggestions,
                    isLoadingWebSuggestions = urlBarSuggestions.isLoadingWebSuggestions,
                    onHistorySuggestionClick = onHistorySuggestionClick,
                    onWebSuggestionClick = onWebSuggestionClick,
                    onCopyCurrentUrl = state::copyCurrentPageUrl,
                    onRestoreCurrentUrl = state::restoreCurrentPageUrlToInput,
                    clipboardUrl = clipboardUrl,
                    onClipboardUrlClick = onClipboardUrlClick,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
internal fun UrlSuggestionList(
    currentPageUrl: String,
    historySuggestions: List<net.matsudamper.browser.data.history.HistoryEntry>,
    webSuggestions: List<String>,
    isLoadingWebSuggestions: Boolean,
    onHistorySuggestionClick: (net.matsudamper.browser.data.history.HistoryEntry) -> Unit,
    onWebSuggestionClick: (String) -> Unit,
    onCopyCurrentUrl: () -> Unit,
    onRestoreCurrentUrl: () -> Unit,
    clipboardUrl: String?,
    onClipboardUrlClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasHistorySuggestions = historySuggestions.isNotEmpty()
    val hasWebSuggestions = webSuggestions.isNotEmpty() || isLoadingWebSuggestions

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        if (currentPageUrl.isNotBlank()) {
            item(key = "current_page_url") {
                CurrentPageUrlListItem(
                    currentPageUrl = currentPageUrl,
                    onCopyCurrentUrl = onCopyCurrentUrl,
                    onRestoreCurrentUrl = onRestoreCurrentUrl,
                )
                if (clipboardUrl != null || hasHistorySuggestions || hasWebSuggestions) {
                    HorizontalDivider()
                }
            }
        }

        if (clipboardUrl != null) {
            item(key = "clipboard_url") {
                ClipboardUrlListItem(
                    clipboardUrl = clipboardUrl,
                    onClick = { onClipboardUrlClick(clipboardUrl) },
                )
                if (hasHistorySuggestions || hasWebSuggestions) {
                    HorizontalDivider()
                }
            }
        }

        if (hasHistorySuggestions) {
            item(key = "history_header") {
                SuggestionSectionHeader(title = "履歴")
            }
            items(historySuggestions, key = { it.id }) { entry ->
                val displayUrl = HistoryUrlFormat.forDisplay(entry.url)
                ListItem(
                    headlineContent = {
                        Text(
                            text = entry.title.ifBlank { displayUrl },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        if (entry.title.isNotBlank()) {
                            Text(
                                text = displayUrl,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    modifier = Modifier.clickable { onHistorySuggestionClick(entry) },
                )
            }
        }

        if (hasHistorySuggestions && hasWebSuggestions) {
            item(key = "history_web_divider") {
                HorizontalDivider()
            }
        }

        if (hasWebSuggestions) {
            item(key = "web_header") {
                SuggestionSectionHeader(
                    title = "Web検索候補",
                    modifier = Modifier.testTag(BrowserTabSurfaceTestTags.WebSuggestionSection.testTag),
                )
            }
            if (isLoadingWebSuggestions && webSuggestions.isEmpty()) {
                item(key = "web_loading") {
                    ListItem(
                        headlineContent = {
                            Text(text = "候補を取得中...")
                        },
                    )
                }
            }
            items(webSuggestions, key = { it }) { suggestion ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = suggestion,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.clickable { onWebSuggestionClick(suggestion) },
                )
            }
        }
    }
}

@Composable
private fun SuggestionSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun ClipboardUrlListItem(
    clipboardUrl: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(text = "コピーしたリンク")
        },
        supportingContent = {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = clipboardUrl,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        modifier = Modifier.clickable { onClick() },
    )
}

@Composable
private fun CurrentPageUrlListItem(
    currentPageUrl: String,
    onCopyCurrentUrl: () -> Unit,
    onRestoreCurrentUrl: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(BrowserTabSurfaceTestTags.CurrentUrlActions.testTag),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "今のURL",
                style = MaterialTheme.typography.bodyLarge,
            )
            androidx.compose.runtime.CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = currentPageUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(BrowserTabSurfaceTestTags.CurrentUrlText.testTag),
                )
            }
        }
        CurrentPageUrlActionRow(
            text = "URLバーに戻す",
            iconPainter = painterResource(R.drawable.ic_arrow_upward),
            onClick = onRestoreCurrentUrl,
            modifier = Modifier.testTag(BrowserTabSurfaceTestTags.RestoreUrlButton.testTag),
        )
        CurrentPageUrlActionRow(
            text = "コピー",
            iconPainter = painterResource(R.drawable.ic_content_copy),
            onClick = onCopyCurrentUrl,
            modifier = Modifier.testTag(BrowserTabSurfaceTestTags.CopyButton.testTag),
        )
    }
}

@Composable
private fun CurrentPageUrlActionRow(
    text: String,
    iconPainter: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = iconPainter,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Preview(name = "今のURL操作Light")
@Composable
private fun PreviewCurrentPageUrlListItemLight() {
    BrowserTheme(themeMode = ThemeMode.THEME_LIGHT) {
        Surface {
            CurrentPageUrlListItem(
                currentPageUrl = "https://example.com/very/long/path?query=value",
                onCopyCurrentUrl = {},
                onRestoreCurrentUrl = {},
            )
        }
    }
}

@Preview(name = "今のURL操作Dark")
@Composable
private fun PreviewCurrentPageUrlListItemDark() {
    BrowserTheme(themeMode = ThemeMode.THEME_DARK) {
        Surface {
            CurrentPageUrlListItem(
                currentPageUrl = "https://example.com/very/long/path?query=value",
                onCopyCurrentUrl = {},
                onRestoreCurrentUrl = {},
            )
        }
    }
}

@Composable
internal fun PageLoadErrorOverlay(
    pageLoadError: PageLoadError,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag(BrowserTabSurfaceTestTags.PageLoadError.testTag),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            androidx.compose.runtime.CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = "ページを表示できません",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = pageLoadError.title,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = pageLoadError.message,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (pageLoadError.failingUrl.isNotBlank()) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text(
                            text = pageLoadError.failingUrl,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag(BrowserTabSurfaceTestTags.RetryButton.testTag),
                ) {
                    Text("再読み込み")
                }
            }
        }
    }
}

sealed interface BrowserTabSurfaceTestTags {
    val id: String
    val testTag get() = "${BrowserTabSurfaceTestTags::class.java.name}#$id"

    object UrlSuggestionList : BrowserTabSurfaceTestTags {
        override val id = "url_suggestion_list"
    }
    object WebSuggestionSection : BrowserTabSurfaceTestTags {
        override val id = "web_suggestion_section"
    }
    object CurrentUrlActions : BrowserTabSurfaceTestTags {
        override val id = "current_url_actions"
    }
    object CurrentUrlText : BrowserTabSurfaceTestTags {
        override val id = "current_url_text"
    }
    object PageLoadError : BrowserTabSurfaceTestTags {
        override val id = "page_load_error"
    }
    object CopyButton : BrowserTabSurfaceTestTags {
        override val id = "copy_button"
    }
    object RestoreUrlButton : BrowserTabSurfaceTestTags {
        override val id = "restore_url_button"
    }
    object RetryButton : BrowserTabSurfaceTestTags {
        override val id = "retry_button"
    }
}
