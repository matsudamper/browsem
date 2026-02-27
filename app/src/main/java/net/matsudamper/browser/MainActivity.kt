package net.matsudamper.browser

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : ComponentActivity() {
    private lateinit var runtime: GeckoRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = GeckoRuntime.getDefault(this)
        setContent {
            BrowserApp(runtime = runtime)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowserApp(runtime: GeckoRuntime) {
    var urlInput by remember { mutableStateOf("https://www.mozilla.org") }
    var loadedUrl by remember { mutableStateOf("https://www.mozilla.org") }
    var canGoBack by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val session = remember {
        GeckoSession().also { it.open(runtime) }
    }

    DisposableEffect(session) {
        val navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, value: Boolean) {
                canGoBack = value
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

    var isUrlInput by remember { mutableStateOf(false) }
    val isImeVisible = WindowInsets.isImeVisible
    LaunchedEffect(isImeVisible) {
        if (isImeVisible.not()) {
            isUrlInput = false
        }
    }

    MaterialTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isImeVisible) {
                            Modifier
                                .imePadding()
                                .padding(top = innerPadding.calculateTopPadding())
                        } else {
                            Modifier.padding(innerPadding)

                        }
                    )
                    .imePadding()
            ) {
                TextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged {
                            if (it.hasFocus) {
                                isUrlInput = true
                            }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val url = urlInput.trim().let {
                                if (!it.startsWith("http://") && !it.startsWith("https://")) "https://$it" else it
                            }
                            urlInput = url
                            loadedUrl = url
                            keyboardController?.hide()
                        }
                    )
                )
                AndroidView(
                    factory = { context ->
                        GeckoView(context).also { geckoView ->
                            geckoView.setSession(session)
                        }
                    },
                    update = { geckoView ->
                        val focus = isUrlInput.not()
                        geckoView.isFocusable = focus
                        geckoView.isFocusableInTouchMode = focus
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
