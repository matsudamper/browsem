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
                }
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = intent.action
                    data = intent.data
                    intent.extras?.let { putExtras(it) }
                }
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
