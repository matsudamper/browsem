package net.matsudamper.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.matsudamper.browser.ui.browser.UrlBarSuggestionsUiState
import net.matsudamper.browser.ui.common.BrowserTheme
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController

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
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            factory = { context ->
                GeckoSwipeRefreshLayout(context).also { swipeRefreshLayout ->
                    var swipeRefreshScrollEnabled = false
                    val gecko = GeckoView(context).also { geckoView ->
                        geckoView.id = id
                        geckoView.isNestedScrollingEnabled = true
                        geckoView.setAutofillEnabled(true)
                        geckoView.importantForAutofill =
                            View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS
                        geckoView.setSession(session)
                        // Engine 側で非アクティブ扱いになると Compositor の描画更新が止まり、
                        // 復帰時の黒画面につながるため、初期生成時に必ず active 化する。
                        session.setActive(true)
                        @SuppressLint("ClickableViewAccessibility")
                        geckoView.setOnTouchListener { view, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                swipeRefreshScrollEnabled = false
                                (view as GeckoView).onTouchEventForDetailResult(event).then { detail ->
                                    if (detail != null) {
                                        val handledResult = detail.handledResult()
                                        val isUnhandled = handledResult == PanZoomController.INPUT_RESULT_UNHANDLED
                                        val isHandled = handledResult == PanZoomController.INPUT_RESULT_HANDLED
                                        swipeRefreshScrollEnabled = isHandled || isUnhandled
                                    }
                                    GeckoResult.fromValue<Void>(null)
                                }
                                true
                            } else {
                                false
                            }
                        }
                    }
                    updateGeckoView(gecko)
                    swipeRefreshLayout.addView(
                        gecko,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    )
                    swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
                        !swipeRefreshScrollEnabled || state.scrollY > 0
                    }
                    swipeRefreshLayout.setOnRefreshListener {
                        state.isRefreshing = true
                        latestOnRefresh()
                    }
                }
            },
            update = { swipeRefreshLayout ->
                swipeRefreshLayout.isRefreshing = state.isRefreshing
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
                    (if (urlBarSuggestions.isLoadingHistorySuggestions) 1 else 0) +
                    (if (urlBarSuggestions.isLoadingWebSuggestions) 1 else 0) +
                    (if (clipboardUrl != null) 1 else 0),
                currentPageUrl = state.currentPageUrl,
            )
        ) {
            UrlSuggestionList(
                currentPageUrl = state.currentPageUrl,
                historySuggestions = urlBarSuggestions.historySuggestions,
                isLoadingHistorySuggestions = urlBarSuggestions.isLoadingHistorySuggestions,
                webSuggestions = urlBarSuggestions.webSuggestions,
                isLoadingWebSuggestions = urlBarSuggestions.isLoadingWebSuggestions,
                onHistorySuggestionClick = onHistorySuggestionClick,
                onWebSuggestionClick = onWebSuggestionClick,
                onCopyCurrentUrl = state::copyCurrentPageUrl,
                onRestoreCurrentUrl = state::restoreCurrentPageUrlToInput,
                clipboardUrl = clipboardUrl,
                onClipboardUrlClick = onClipboardUrlClick,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .testTag(BrowserTabSurfaceTestTags.UrlSuggestionList.testTag),
            )
        }
    }
}

@Composable
internal fun UrlSuggestionList(
    currentPageUrl: String,
    historySuggestions: List<net.matsudamper.browser.data.history.HistoryEntry>,
    isLoadingHistorySuggestions: Boolean,
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
    val hasHistorySuggestions = historySuggestions.isNotEmpty() || isLoadingHistorySuggestions
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
            if (isLoadingHistorySuggestions && historySuggestions.isEmpty()) {
                item(key = "history_loading") {
                    ListItem(
                        headlineContent = {
                            Text(text = "候補を取得中...")
                        },
                    )
                }
            }
            items(historySuggestions, key = { it.id }) { entry ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = entry.title.ifBlank { entry.url },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        if (entry.title.isNotBlank()) {
                            Text(
                                text = entry.url,
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
    ListItem(
        headlineContent = {
            Text(text = "今のURL")
        },
        supportingContent = {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = currentPageUrl,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(BrowserTabSurfaceTestTags.CurrentUrlText.testTag),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onCopyCurrentUrl,
                        modifier = Modifier.testTag(BrowserTabSurfaceTestTags.CopyButton.testTag),
                    ) {
                        Text("コピー")
                    }
                    TextButton(
                        onClick = onRestoreCurrentUrl,
                        modifier = Modifier.testTag(BrowserTabSurfaceTestTags.RestoreUrlButton.testTag),
                    ) {
                        Text("URLバーに戻す")
                    }
                }
            }
        },
        modifier = Modifier.testTag(BrowserTabSurfaceTestTags.CurrentUrlActions.testTag),
    )
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

@Preview(name = "履歴ローディング中")
@Composable
private fun PreviewUrlSuggestionListHistoryLoading() {
    BrowserTheme(themeMode = net.matsudamper.browser.data.ThemeMode.THEME_SYSTEM) {
        UrlSuggestionList(
            currentPageUrl = "https://example.com",
            historySuggestions = emptyList(),
            isLoadingHistorySuggestions = true,
            webSuggestions = emptyList(),
            isLoadingWebSuggestions = false,
            onHistorySuggestionClick = {},
            onWebSuggestionClick = {},
            onCopyCurrentUrl = {},
            onRestoreCurrentUrl = {},
            clipboardUrl = null,
            onClipboardUrlClick = {},
        )
    }
}

@Preview(name = "履歴・Webローディング中")
@Composable
private fun PreviewUrlSuggestionListAllLoading() {
    BrowserTheme(themeMode = net.matsudamper.browser.data.ThemeMode.THEME_SYSTEM) {
        UrlSuggestionList(
            currentPageUrl = "https://example.com",
            historySuggestions = emptyList(),
            isLoadingHistorySuggestions = true,
            webSuggestions = emptyList(),
            isLoadingWebSuggestions = true,
            onHistorySuggestionClick = {},
            onWebSuggestionClick = {},
            onCopyCurrentUrl = {},
            onRestoreCurrentUrl = {},
            clipboardUrl = null,
            onClipboardUrlClick = {},
        )
    }
}

sealed interface BrowserTabSurfaceTestTags {
    val id: String
    val testTag get() = "${BrowserTabSurfaceTestTags::class.java.name}#$id"

    object UrlSuggestionList : BrowserTabSurfaceTestTags { override val id = "url_suggestion_list" }
    object WebSuggestionSection : BrowserTabSurfaceTestTags { override val id = "web_suggestion_section" }
    object CurrentUrlActions : BrowserTabSurfaceTestTags { override val id = "current_url_actions" }
    object CurrentUrlText : BrowserTabSurfaceTestTags { override val id = "current_url_text" }
    object PageLoadError : BrowserTabSurfaceTestTags { override val id = "page_load_error" }
    object CopyButton : BrowserTabSurfaceTestTags { override val id = "copy_button" }
    object RestoreUrlButton : BrowserTabSurfaceTestTags { override val id = "restore_url_button" }
    object RetryButton : BrowserTabSurfaceTestTags { override val id = "retry_button" }
}
