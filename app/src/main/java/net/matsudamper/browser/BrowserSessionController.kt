package net.matsudamper.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

internal class BrowserSessionController(runtime: GeckoRuntime) {
    val session: GeckoSession = GeckoSession().also { it.open(runtime) }
    private var initialPageLoaded = false

    fun ensureInitialPageLoaded(homepageUrl: String) {
        if (initialPageLoaded) {
            return
        }
        initialPageLoaded = true
        session.loadUri(homepageUrl)
    }

    fun close() {
        session.close()
    }
}

@Composable
internal fun rememberBrowserSessionController(runtime: GeckoRuntime): BrowserSessionController {
    val controller = remember(runtime) { BrowserSessionController(runtime) }
    DisposableEffect(controller) {
        onDispose {
            controller.close()
        }
    }
    return controller
}
