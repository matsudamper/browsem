package net.matsudamper.browser

import android.content.Intent

/**
 * VIEW Intent を新規タブ要求として処理済みかを、Intent 自体に付けた印で判定する。
 *
 * URL で判定すると、Activity が再生成されつつ新しい Intent を受け取ったとき
 * （プロセス終了後に同じ URL を開き直す等）に新しい要求まで既処理と見なして取りこぼす。
 * 印を載せた Intent を getIntent() に保持しておけば、設定変更後の onCreate で
 * 同じ Intent を再処理しない。
 */
object DeepLinkIntentConsumption {
    private const val EXTRA_CONSUMED = "net.matsudamper.browser.extra.DEEP_LINK_CONSUMED"

    fun isConsumed(intent: Intent): Boolean {
        return intent.getBooleanExtra(EXTRA_CONSUMED, false)
    }

    fun markConsumed(intent: Intent) {
        intent.putExtra(EXTRA_CONSUMED, true)
    }
}
