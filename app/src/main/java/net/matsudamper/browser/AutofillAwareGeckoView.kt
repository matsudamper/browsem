package net.matsudamper.browser

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewStructure
import android.view.autofill.AutofillManager
import org.mozilla.geckoview.GeckoView

internal class AutofillAwareGeckoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : GeckoView(context, attrs) {
    var currentPageUrl: String = ""

    private val autofillManager: AutofillManager? by lazy(LazyThreadSafetyMode.NONE) {
        context.getSystemService(AutofillManager::class.java)
    }

    override fun onProvideAutofillVirtualStructure(structure: ViewStructure, flags: Int) {
        super.onProvideAutofillVirtualStructure(structure, flags)
        parseWebDomain(currentPageUrl)?.let { domain ->
            structure.setWebDomain(domain)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            requestPlatformAutofill()
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) {
            requestPlatformAutofill()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            requestPlatformAutofill()
        }
        return super.onTouchEvent(event)
    }

    fun requestPlatformAutofill() {
        autofillManager?.requestAutofill(this)
    }

    private fun parseWebDomain(url: String): String? {
        if (url.isBlank()) {
            return null
        }
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") {
            return null
        }
        return uri.host?.takeIf { it.isNotBlank() }
    }
}
