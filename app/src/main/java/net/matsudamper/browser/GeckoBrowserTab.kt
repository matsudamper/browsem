package net.matsudamper.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DEFAULT_URL = "https://webauthn.io"

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun GeckoBrowserTab(
    runtime: GeckoRuntime,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var urlInput by rememberSaveable { mutableStateOf(DEFAULT_URL) }
    var loadedUrl by rememberSaveable { mutableStateOf(DEFAULT_URL) }
    var customHomeUrl by rememberSaveable { mutableStateOf(DEFAULT_URL) }
    var pageTitle by rememberSaveable { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isUrlInputFocused by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableFloatStateOf(0f) }
    var isDesktopMode by rememberSaveable { mutableStateOf(false) }
    var pageStatusText by rememberSaveable { mutableStateOf("待機中") }
    var lastVisitedAt by rememberSaveable { mutableStateOf("-") }
    var visitCount by rememberSaveable { mutableIntStateOf(0) }
    var hideControls by rememberSaveable { mutableStateOf(false) }

    val bookmarks = rememberSaveable { mutableStateListOf<String>() }
    val history = rememberSaveable { mutableStateListOf<String>() }

    val keyboardController = LocalSoftwareKeyboardController.current
    val isImeVisible = WindowInsets.isImeVisible

    val session = remember(runtime) {
        GeckoSession().also { it.open(runtime) }
    }

    DisposableEffect(session, isDesktopMode) {
        session.settings.userAgentMode = if (isDesktopMode) {
            GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }

        val navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, value: Boolean) {
                canGoBack = value
            }

            override fun onCanGoForward(session: GeckoSession, value: Boolean) {
                canGoForward = value
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                permissions: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (url.isNullOrBlank().not()) {
                    loadedUrl = url
                    if (!isUrlInputFocused) {
                        urlInput = url
                    }
                    if (history.lastOrNull() != url) {
                        history.add(0, url)
                        if (history.size > 20) {
                            history.removeAt(history.lastIndex)
                        }
                    }
                }
            }
        }

        val progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                isLoading = true
                loadingProgress = 0f
                pageStatusText = "読み込み開始"
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                isLoading = false
                loadingProgress = 1f
                pageStatusText = if (success) "読み込み完了" else "読み込み失敗"
                if (success) {
                    visitCount += 1
                    lastVisitedAt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                loadingProgress = progress / 100f
            }
        }

        val contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                pageTitle = title.orEmpty()
            }
        }

        session.navigationDelegate = navigationDelegate
        session.progressDelegate = progressDelegate
        session.contentDelegate = contentDelegate

        onDispose {
            if (session.navigationDelegate === navigationDelegate) {
                session.navigationDelegate = null
            }
            if (session.progressDelegate === progressDelegate) {
                session.progressDelegate = null
            }
            if (session.contentDelegate === contentDelegate) {
                session.contentDelegate = null
            }
            session.close()
        }
    }

    BackHandler(enabled = canGoBack) {
        session.goBack()
    }

    LaunchedEffect(loadedUrl) {
        session.loadUri(loadedUrl)
    }

    LaunchedEffect(isImeVisible) {
        if (!isImeVisible) {
            isUrlInputFocused = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        if (!hideControls) {
            val host = runCatching { Uri.parse(loadedUrl).host.orEmpty() }.getOrDefault("")
            val isSecure = loadedUrl.startsWith("https://")

            Text(
                text = if (isSecure) "🔒 $host" else "⚠️ $host",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )

            if (pageTitle.isNotBlank()) {
                Text(
                    text = pageTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            Text(
                text = "状態: $pageStatusText / 訪問回数: $visitCount / 最終: $lastVisitedAt",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )

            BrowserUrlTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                onSubmit = { submittedUrl ->
                    urlInput = submittedUrl
                    loadedUrl = submittedUrl
                    keyboardController?.hide()
                },
                onFocusChanged = { hasFocus -> isUrlInputFocused = hasFocus }
            )

            if (isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { loadingProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(text = "読み込み中…")
                }
                LinearProgressIndicator(
                    progress = { loadingProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                OutlinedButton(onClick = { session.goBack() }, enabled = canGoBack) { Text("戻る") }
                OutlinedButton(onClick = { session.goForward() }, enabled = canGoForward) { Text("進む") }
                OutlinedButton(onClick = { session.reload() }) { Text("再読込") }
                OutlinedButton(onClick = { if (isLoading) session.stop() }, enabled = isLoading) { Text("停止") }
                OutlinedButton(onClick = {
                    loadedUrl = customHomeUrl
                    urlInput = customHomeUrl
                }) { Text("ホーム") }
                OutlinedButton(onClick = { customHomeUrl = loadedUrl }) { Text("ホーム設定") }
                OutlinedButton(onClick = { copyToClipboard(context, loadedUrl) }) { Text("URLコピー") }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                val currentUrl = loadedUrl
                val isBookmarked = bookmarks.contains(currentUrl)
                OutlinedButton(onClick = {
                    if (isBookmarked) {
                        bookmarks.remove(currentUrl)
                    } else {
                        bookmarks.add(currentUrl)
                    }
                }) {
                    Text(if (isBookmarked) "★解除" else "★保存")
                }
                OutlinedButton(onClick = {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, currentUrl)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "URLを共有"))
                }) {
                    Text("共有")
                }
                OutlinedButton(onClick = {
                    val externalIntent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                    context.startActivity(externalIntent)
                }) {
                    Text("外部で開く")
                }
                OutlinedButton(onClick = {
                    loadedUrl = "view-source:$currentUrl"
                    urlInput = loadedUrl
                }) {
                    Text("ソース表示")
                }
                Button(onClick = {
                    isDesktopMode = !isDesktopMode
                    session.reload()
                }) {
                    Text(if (isDesktopMode) "PC表示中" else "モバイル表示中")
                }
                OutlinedButton(onClick = { hideControls = true }) { Text("UI最小化") }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                OutlinedButton(onClick = {
                    loadedUrl = "https://www.google.com/search?q=${Uri.encode(urlInput)}"
                }) { Text("Google検索") }
                OutlinedButton(onClick = {
                    loadedUrl = "https://duckduckgo.com/?q=${Uri.encode(urlInput)}"
                }) { Text("DuckDuckGo") }
                OutlinedButton(onClick = {
                    loadedUrl = "https://ja.wikipedia.org/wiki/Special:Search?search=${Uri.encode(urlInput)}"
                }) { Text("Wikipedia") }
                OutlinedButton(onClick = { urlInput = loadedUrl }) { Text("URL貼付(現在)") }
                OutlinedButton(onClick = {
                    if (history.isNotEmpty()) {
                        loadedUrl = history.first()
                    }
                }, enabled = history.isNotEmpty()) { Text("最新履歴へ") }
            }

            if (bookmarks.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "ブックマーク(${bookmarks.size})",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    bookmarks.take(5).forEach { bookmarkUrl ->
                        OutlinedButton(onClick = {
                            urlInput = bookmarkUrl
                            loadedUrl = bookmarkUrl
                        }) {
                            Text(
                                text = bookmarkUrl,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    OutlinedButton(onClick = { bookmarks.clear() }) { Text("全削除") }
                }
            }

            if (history.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "履歴(${history.size})",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    history.take(5).forEach { historyUrl ->
                        OutlinedButton(onClick = {
                            urlInput = historyUrl
                            loadedUrl = historyUrl
                        }) {
                            Text(
                                text = historyUrl,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    OutlinedButton(onClick = { history.clear() }) { Text("履歴削除") }
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                OutlinedButton(onClick = { hideControls = false }) { Text("UI再表示") }
                OutlinedButton(onClick = { session.reload() }) { Text("再読込") }
            }
        }

        AndroidView(
            factory = { contextForView ->
                GeckoView(contextForView).also { geckoView ->
                    geckoView.setSession(session)
                }
            },
            update = { geckoView ->
                val shouldFocusWebContent = isUrlInputFocused.not()
                geckoView.isFocusable = shouldFocusWebContent
                geckoView.isFocusableInTouchMode = shouldFocusWebContent
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("url", value))
}
