package net.matsudamper.browser

import android.content.Context
import net.matsudamper.browser.media.MediaWebExtension
import org.mozilla.geckoview.GeckoRuntime

class BrowserRuntimeCoordinator(
    appContext: Context,
    val runtime: GeckoRuntime,
) {
    val browserSessionController = BrowserSessionController(runtime)
    val themeColorExtension = ThemeColorWebExtension().also { it.install(runtime) }
    val mediaWebExtension = MediaWebExtension(appContext).also { it.install(runtime) }

    fun applyRuntimeSettings(enableThirdPartyCa: Boolean) {
        runtime.settings.setEnterpriseRootsEnabled(enableThirdPartyCa)
    }

    fun close() {
        browserSessionController.close()
        themeColorExtension.cleanup()
        mediaWebExtension.cleanup()
    }
}
