package net.matsudamper.browser

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
    var pageTitle by rememberSaveable { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isUrlInputFocused by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableFloatStateOf(0f) }
    var isDesktopMode by rememberSaveable { mutableStateOf(false) }
    val bookmarks = rememberSaveable { mutableStateListOf<String>() }

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
                }
            }
        }

        val progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                isLoading = true
                loadingProgress = 0f
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                isLoading = false
                loadingProgress = 1f
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
        if (pageTitle.isNotBlank()) {
            Text(
                text = pageTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

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
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            OutlinedButton(onClick = { session.goBack() }, enabled = canGoBack) { Text("戻る") }
            OutlinedButton(onClick = { session.goForward() }, enabled = canGoForward) { Text("進む") }
            OutlinedButton(onClick = { session.reload() }) { Text("再読込") }
            OutlinedButton(onClick = { loadedUrl = DEFAULT_URL }) { Text("ホーム") }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            Button(onClick = {
                isDesktopMode = !isDesktopMode
                loadedUrl = loadedUrl
            }) {
                Text(if (isDesktopMode) "PC表示" else "モバイル")
            }
        }

        if (bookmarks.isNotEmpty()) {
            HorizontalDivider()
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                bookmarks.take(3).forEach { bookmarkUrl ->
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
