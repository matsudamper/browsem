package net.matsudamper.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController

internal fun isThemeColorForCurrentPage(currentPageUrl: String, reportedUrl: String): Boolean {
    if (reportedUrl.isBlank()) return false
    return normalizedThemeColorUrlKey(currentPageUrl) == normalizedThemeColorUrlKey(reportedUrl)
}

private fun normalizedThemeColorUrlKey(url: String): String {
    return url
        .substringBefore("#")
        .removeSuffix("/")
}

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
                SwipeRefreshLayout(context).also { swipeRefreshLayout ->
                    var swipeRefreshScrollEnabled = false
                    val gecko = GeckoView(context).also { geckoView ->
                        geckoView.id = id
                        geckoView.isNestedScrollingEnabled = true
                        geckoView.setAutofillEnabled(true)
                        geckoView.importantForAutofill =
                            View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS
                        geckoView.setSession(session)
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
                if (!state.isUrlInputFocused && !geckoView.isFocused) {
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
            previewBitmap?.also { bitmap ->
                Image(
                    modifier = Modifier.fillMaxSize(),
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    alignment = androidx.compose.ui.Alignment.TopStart,
                )
            }
        }
    }
}

@Composable
internal fun HistorySuggestionList(
    currentPageUrl: String,
    suggestions: List<net.matsudamper.browser.data.history.HistoryEntry>,
    onSuggestionClick: (net.matsudamper.browser.data.history.HistoryEntry) -> Unit,
    onCopyCurrentUrl: () -> Unit,
    onRestoreCurrentUrl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        if (currentPageUrl.isNotBlank()) {
            item(key = "current_page_url") {
                CurrentPageUrlListItem(
                    currentPageUrl = currentPageUrl,
                    onCopyCurrentUrl = onCopyCurrentUrl,
                    onRestoreCurrentUrl = onRestoreCurrentUrl,
                )
                if (suggestions.isNotEmpty()) {
                    HorizontalDivider()
                }
            }
        }
        items(suggestions, key = { it.id }) { entry ->
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
                modifier = Modifier.clickable { onSuggestionClick(entry) },
            )
        }
    }
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
                    modifier = Modifier.testTag(TEST_TAG_CURRENT_URL_TEXT),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCopyCurrentUrl) {
                        Text("コピー")
                    }
                    TextButton(onClick = onRestoreCurrentUrl) {
                        Text("URLバーに戻す")
                    }
                }
            }
        },
        modifier = Modifier.testTag(TEST_TAG_CURRENT_URL_ACTIONS),
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
            .testTag(TEST_TAG_PAGE_LOAD_ERROR),
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
                TextButton(onClick = onRetry) {
                    Text("再読み込み")
                }
            }
        }
    }
}

const val TEST_TAG_GECKO_CONTAINER = "gecko_container"
const val TEST_TAG_HISTORY_SUGGESTION_LIST = "history_suggestion_list"
const val TEST_TAG_CURRENT_URL_ACTIONS = "current_url_actions"
const val TEST_TAG_CURRENT_URL_TEXT = "current_url_text"
const val TEST_TAG_PAGE_LOAD_ERROR = "page_load_error"
