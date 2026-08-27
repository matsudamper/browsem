package net.matsudamper.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import org.mozilla.geckoview.GeckoView

/**
 * 住所・メールの候補を [InputMethodManager.displayCompletions] で IME に渡す GeckoView。
 *
 * Gecko の InputConnection は commitCompletion を扱わないため、返却する
 * InputConnection を包んで選択を受け取る。
 */
internal class AddressAutofillGeckoView(context: Context) : GeckoView(context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var pendingCompletions: Array<CompletionInfo> = emptyArray()

    var onCompletionPicked: ((Int) -> Unit)? = null

    fun showCompletions(completions: List<CompletionInfo>) {
        synchronized(lock) {
            pendingCompletions = completions.toTypedArray()
        }
        postDisplay()
        mainHandler.postDelayed({ postDisplay() }, DISPLAY_RETRY_SHORT_MS)
        mainHandler.postDelayed({ postDisplay() }, DISPLAY_RETRY_LONG_MS)
    }

    fun clearCompletions() {
        synchronized(lock) {
            pendingCompletions = emptyArray()
        }
        postDisplay()
    }

    fun displayedCompletionCount(): Int {
        return synchronized(lock) { pendingCompletions.size }
    }

    fun pickCompletionAt(index: Int): Boolean {
        val count = synchronized(lock) { pendingCompletions.size }
        if (index !in 0 until count) return false
        onCompletionPicked?.invoke(index)
        clearCompletions()
        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        if (outAttrs.inputType != EditorInfo.TYPE_NULL) {
            outAttrs.inputType = outAttrs.inputType or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE
        }
        mainHandler.post { postDisplay() }
        return AddressAutofillInputConnection(base) { info ->
            mainHandler.post {
                onCompletionPicked?.invoke(info.position)
                clearCompletions()
            }
            true
        }
    }

    private fun postDisplay() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        val completions = synchronized(lock) { pendingCompletions }
        imm.displayCompletions(this, completions)
    }

    private class AddressAutofillInputConnection(
        private val wrapped: InputConnection,
        private val onCommitCompletion: (CompletionInfo) -> Boolean,
    ) : InputConnection by wrapped {
        override fun commitCompletion(text: CompletionInfo): Boolean {
            return onCommitCompletion(text)
        }
    }

    private companion object {
        private const val DISPLAY_RETRY_SHORT_MS = 250L
        private const val DISPLAY_RETRY_LONG_MS = 800L
    }
}
