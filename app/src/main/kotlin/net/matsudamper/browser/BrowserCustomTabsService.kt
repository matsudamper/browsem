package net.matsudamper.browser

import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsSessionToken
import org.koin.android.ext.android.inject

class BrowserCustomTabsService : CustomTabsService() {
    private val warmupStore: CustomTabsWarmupStore by inject()

    override fun warmup(flags: Long): Boolean {
        warmupStore.onWarmup()
        return true
    }

    override fun newSession(sessionToken: CustomTabsSessionToken): Boolean {
        warmupStore.onNewSession(sessionToken)
        return true
    }

    override fun mayLaunchUrl(
        sessionToken: CustomTabsSessionToken,
        url: Uri?,
        extras: Bundle?,
        otherLikelyBundles: List<Bundle>?,
    ): Boolean {
        warmupStore.onMayLaunchUrl(
            token = sessionToken,
            url = url,
        )
        return true
    }

    override fun extraCommand(commandName: String, args: Bundle?): Bundle? = null

    override fun updateVisuals(sessionToken: CustomTabsSessionToken, bundle: Bundle?): Boolean = true

    override fun requestPostMessageChannel(
        sessionToken: CustomTabsSessionToken,
        postMessageOrigin: Uri,
    ): Boolean = false

    override fun postMessage(
        sessionToken: CustomTabsSessionToken,
        message: String,
        extras: Bundle?,
    ): Int = RESULT_FAILURE_DISALLOWED

    override fun validateRelationship(
        sessionToken: CustomTabsSessionToken,
        relation: Int,
        origin: Uri,
        extras: Bundle?,
    ): Boolean = false

    override fun receiveFile(
        sessionToken: CustomTabsSessionToken,
        uri: Uri,
        purpose: Int,
        extras: Bundle?,
    ): Boolean = false

    override fun cleanUpSession(sessionToken: CustomTabsSessionToken): Boolean {
        warmupStore.onSessionCleanup(sessionToken)
        return super.cleanUpSession(sessionToken)
    }
}
