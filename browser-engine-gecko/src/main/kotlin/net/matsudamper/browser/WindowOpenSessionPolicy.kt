package net.matsudamper.browser

import android.os.Handler
import android.os.Looper
import android.view.Choreographer

/**
 * `window.open` / `onNewSession` のセッション契約。
 *
 * 未開封の [org.mozilla.geckoview.GeckoSession] を返したあと、同じコールバック内で
 * opener の `GeckoView` を破棄してはいけない。Gecko が opener 関係を張る前に親が
 * 外れると `window.open()` が null になり、決済などがブロック扱いにされる。
 *
 * 通常ブラウザでは新規タブとして開く。タブ切替はコールバック完了後に回す。
 */
object WindowOpenSessionPolicy {
    fun postToMain(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post(action)
    }

    fun postAfterFrame(action: () -> Unit) {
        Choreographer.getInstance().postFrameCallback {
            action()
        }
    }

    /**
     * `onNewSession` コールバックの外側でタブ切替し、Compose が opener の View を
     * 外したあとに opener を再 active する。
     */
    fun scheduleSelectAfterCallback(
        postToMain: (() -> Unit) -> Unit = ::postToMain,
        postAfterFrame: (() -> Unit) -> Unit = ::postAfterFrame,
        selectTab: () -> Unit,
        retainOpeners: () -> Unit,
    ) {
        postToMain {
            selectTab()
            postAfterFrame(retainOpeners)
        }
    }
}
