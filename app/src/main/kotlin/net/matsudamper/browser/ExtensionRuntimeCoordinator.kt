package net.matsudamper.browser

import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

/**
 * 拡張機能全体を再有効化したあと、MainActivity 側で delegate を再設定するためのフック。
 */
internal class ExtensionRuntimeCoordinator(
    private val runtime: GeckoRuntime,
) {
    private var onExtensionReady: ((WebExtension) -> Unit)? = null

    fun setOnExtensionReady(handler: (WebExtension) -> Unit) {
        onExtensionReady = handler
    }

    fun clearOnExtensionReady() {
        onExtensionReady = null
    }

    fun notifyExtensionsGloballyEnabled() {
        val handler = onExtensionReady ?: return
        runtime.webExtensionController.list().accept(
            { extensions -> extensions?.forEach(handler) },
            {},
        )
    }
}
