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
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

private const val DEFAULT_URL = "https://webauthn.io"
private const val MAX_HISTORY = 100

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun GeckoBrowserTab(
    runtime: GeckoRuntime,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var urlInput by rememberSaveable { mutableStateOf(DEFAULT_URL) }
    var loadedUrl by rememberSaveable { mutableStateOf(DEFAULT_URL) }
    var homeUrl by rememberSaveable { mutableStateOf(DEFAULT_URL) }
    var pageTitle by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isUrlFocused by remember { mutableStateOf(false) }
    var isDesktopMode by rememberSaveable { mutableStateOf(false) }

    val bookmarks = rememberSaveable { mutableStateListOf<String>() }
    val history = rememberSaveable { mutableStateListOf<String>() }

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
                    if (!isUrlFocused) {
                        urlInput = url
                    }
                    if (history.firstOrNull() != url) {
                        history.add(0, url)
                        if (history.size > MAX_HISTORY) {
                            history.removeAt(history.lastIndex)
                        }
                    }
                }
            }
        }

        val progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                isLoading = true
                progress = 0f
            }

            override fun onProgressChange(session: GeckoSession, progressValue: Int) {
                progress = progressValue / 100f
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                isLoading = false
                progress = 1f
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
            isUrlFocused = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        val host = runCatching { Uri.parse(loadedUrl).host.orEmpty() }.getOrDefault("")
        val isSecure = loadedUrl.startsWith("https://")

        Text(
            text = if (isSecure) "🔒 $host" else "⚠️ $host",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )

        if (pageTitle.isNotBlank()) {
            Text(
                text = pageTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        BrowserUrlTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            onSubmit = {
                urlInput = it
                loadedUrl = it
                keyboardController?.hide()
            },
            onFocusChanged = { isUrlFocused = it }
        )

        if (isLoading) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(8.dp)
        ) {
            OutlinedButton(onClick = { session.goBack() }, enabled = canGoBack) { Text("戻る") }
            OutlinedButton(onClick = { session.goForward() }, enabled = canGoForward) { Text("進む") }
            OutlinedButton(onClick = {
                if (isLoading) session.stop() else session.reload()
            }) { Text(if (isLoading) "停止" else "再読込") }
            OutlinedButton(onClick = {
                loadedUrl = homeUrl
                urlInput = homeUrl
            }) { Text("ホーム") }
            OutlinedButton(onClick = { homeUrl = loadedUrl }) { Text("ホーム設定") }
            FilterChip(
                selected = isDesktopMode,
                onClick = {
                    isDesktopMode = !isDesktopMode
                    session.reload()
                },
                label = { Text(if (isDesktopMode) "PC表示" else "モバイル表示") }
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            val currentUrl = loadedUrl
            val isBookmarked = bookmarks.contains(currentUrl)
            OutlinedButton(onClick = {
                if (isBookmarked) bookmarks.remove(currentUrl) else bookmarks.add(currentUrl)
            }) { Text(if (isBookmarked) "★解除" else "★保存") }
            OutlinedButton(onClick = {
                copyToClipboard(context, currentUrl)
            }) { Text("URLコピー") }
            OutlinedButton(onClick = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                }
                context.startActivity(Intent.createChooser(shareIntent, "URLを共有"))
            }) { Text("共有") }
            OutlinedButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
            }) { Text("外部で開く") }
        }

        if (bookmarks.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            Text(
                text = "ブックマーク (${bookmarks.size})",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                bookmarks.take(10).forEach { bookmarkUrl ->
                    OutlinedButton(onClick = {
                        urlInput = bookmarkUrl
                        loadedUrl = bookmarkUrl
                    }) {
                        Text(bookmarkUrl, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (history.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(
                    text = "履歴 (${history.size})",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { history.clear() }) { Text("履歴削除") }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                history.take(10).forEach { historyUrl ->
                    OutlinedButton(onClick = {
                        urlInput = historyUrl
                        loadedUrl = historyUrl
                    }) {
                        Text(historyUrl, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        AndroidView(
            factory = { contextForView ->
                GeckoView(contextForView).also { geckoView ->
                    geckoView.setSession(session)
                }
            },
            update = { geckoView ->
                val shouldFocusWebContent = !isUrlFocused
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
