package net.matsudamper.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

private const val DEFAULT_URL = "https://webauthn.io"

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun GeckoBrowserTab(
    runtime: GeckoRuntime,
    modifier: Modifier = Modifier,
) {
    var urlInput by rememberSaveable { mutableStateOf(DEFAULT_URL) }
    var loadedUrl by rememberSaveable { mutableStateOf(DEFAULT_URL) }
    var canGoBack by remember { mutableStateOf(false) }
    var isUrlInputFocused by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isImeVisible = WindowInsets.isImeVisible

    val session = remember(runtime) {
        GeckoSession().also { it.open(runtime) }
    }

    DisposableEffect(session) {
        val navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, value: Boolean) {
                canGoBack = value
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (!isUrlInputFocused) {
                    urlInput = url.orEmpty()
                }
            }
        }
        session.navigationDelegate = navigationDelegate

        onDispose {
            if (session.navigationDelegate === navigationDelegate) {
                session.navigationDelegate = null
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

        AndroidView(
            factory = { context ->
                GeckoView(context).also { geckoView ->
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
