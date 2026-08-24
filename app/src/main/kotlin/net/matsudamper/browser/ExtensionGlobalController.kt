package net.matsudamper.browser

import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * 拡張機能全体の有効/無効 (APP ソース) と、ユーザー個別設定 (USER ソース) を扱う。
 */
internal object ExtensionGlobalController {
    fun isUserEnabled(extension: WebExtension): Boolean {
        return extension.metaData.disabledFlags and WebExtension.DisabledFlags.USER == 0
    }

    fun applyGlobalEnabled(
        runtime: GeckoRuntime,
        extensions: List<WebExtension>,
        globallyEnabled: Boolean,
        onComplete: () -> Unit = {},
        onError: (Throwable?) -> Unit = {},
    ) {
        applySequentially(
            runtime = runtime,
            extensions = extensions,
            globallyEnabled = globallyEnabled,
            index = 0,
            onComplete = onComplete,
            onError = onError,
        )
    }

    private fun applySequentially(
        runtime: GeckoRuntime,
        extensions: List<WebExtension>,
        globallyEnabled: Boolean,
        index: Int,
        onComplete: () -> Unit,
        onError: (Throwable?) -> Unit,
    ) {
        if (index >= extensions.size) {
            onComplete()
            return
        }
        val extension = extensions[index]
        val result = if (globallyEnabled) {
            runtime.webExtensionController.enable(extension, WebExtensionController.EnableSource.APP)
        } else {
            runtime.webExtensionController.disable(extension, WebExtensionController.EnableSource.APP)
        }
        result.accept(
            {
                applySequentially(
                    runtime = runtime,
                    extensions = extensions,
                    globallyEnabled = globallyEnabled,
                    index = index + 1,
                    onComplete = onComplete,
                    onError = onError,
                )
            },
            onError,
        )
    }
}
