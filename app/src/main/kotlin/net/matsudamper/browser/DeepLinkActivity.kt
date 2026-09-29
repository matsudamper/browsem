package net.matsudamper.browser

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsSessionToken

class DeepLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.isCustomTabLaunchIntent()) {
            startActivity(
                Intent(this, CustomTabActivity::class.java).apply {
                    action = intent.action
                    data = intent.data
                    intent.extras?.let { putExtras(it) }
                    if (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0) {
                        // 呼び出し元のタスクに載らない起動では、既存のブラウザタスクへ積むと
                        // MainActivity(singleTask) の再表示で破棄され、呼び出し元にも戻れない。
                        // Chrome と同様に独立した Recents エントリとして開く。
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                                Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
                        )
                    }
                },
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = intent.action
                    data = intent.data
                    intent.extras?.let { putExtras(it) }
                },
            )
        }
        finish()
    }

    private fun Intent.isCustomTabLaunchIntent(): Boolean {
        if (action != Intent.ACTION_VIEW) return false
        if (CustomTabsSessionToken.getSessionTokenFromIntent(this) != null) {
            return true
        }
        val extras = extras ?: return false
        return extras.containsKey(EXTRA_CUSTOM_TABS_SESSION) ||
            extras.containsKey(EXTRA_CUSTOM_TABS_SESSION_ID)
    }

    companion object {
        private const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
        private const val EXTRA_CUSTOM_TABS_SESSION_ID = "androidx.browser.customtabs.extra.SESSION_ID"
    }
}
