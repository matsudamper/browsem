package net.matsudamper.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.util.Locale

internal data class PendingExternalAppLaunch(
    val sourceUri: String,
    val intent: Intent,
    val appName: String?,
    val fallbackUrl: String?,
)

internal sealed interface ExternalAppNavigationAction {
    data object AllowInBrowser : ExternalAppNavigationAction
    data class Launch(val request: PendingExternalAppLaunch) : ExternalAppNavigationAction
    data class OpenFallback(val url: String) : ExternalAppNavigationAction
    data object AppNotFound : ExternalAppNavigationAction
}

internal fun resolveExternalAppNavigationAction(
    context: Context,
    uri: String,
): ExternalAppNavigationAction {
    val parsedUri = runCatching { Uri.parse(uri) }.getOrNull()
        ?: return ExternalAppNavigationAction.AllowInBrowser
    val scheme = parsedUri.scheme?.lowercase(Locale.US)
        ?: return ExternalAppNavigationAction.AllowInBrowser
    if (scheme in browserHandledSchemes) {
        return ExternalAppNavigationAction.AllowInBrowser
    }

    val intent = buildExternalIntent(uri = uri, parsedUri = parsedUri, scheme = scheme)
        ?: return ExternalAppNavigationAction.AllowInBrowser
    val fallbackUrl = intent.getStringExtra(EXTRA_BROWSER_FALLBACK_URL)?.takeIf { it.isNotBlank() }
    intent.removeExtra(EXTRA_BROWSER_FALLBACK_URL)

    val packageManager = context.packageManager
    val resolvedActivity = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ?: packageManager.resolveActivity(intent, 0)
    if (resolvedActivity == null) {
        return if (fallbackUrl != null) {
            ExternalAppNavigationAction.OpenFallback(fallbackUrl)
        } else {
            ExternalAppNavigationAction.AppNotFound
        }
    }

    val appName = resolvedActivity.loadLabel(packageManager).toString().takeIf { it.isNotBlank() }
    return ExternalAppNavigationAction.Launch(
        PendingExternalAppLaunch(
            sourceUri = uri,
            intent = intent,
            appName = appName,
            fallbackUrl = fallbackUrl,
        )
    )
}

internal fun launchExternalApp(
    context: Context,
    request: PendingExternalAppLaunch,
): Result<Unit> {
    return runCatching {
        if (context !is Activity) {
            request.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(request.intent)
    }
}

private fun buildExternalIntent(
    uri: String,
    parsedUri: Uri,
    scheme: String,
): Intent? {
    val baseIntent = if (scheme == INTENT_SCHEME) {
        runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()
    } else {
        Intent(Intent.ACTION_VIEW, parsedUri)
    } ?: return null

    return baseIntent.apply {
        action = Intent.ACTION_VIEW
        addCategory(Intent.CATEGORY_BROWSABLE)
        component = null
        selector = null
    }
}

private const val INTENT_SCHEME = "intent"
private const val EXTRA_BROWSER_FALLBACK_URL = "browser_fallback_url"

private val browserHandledSchemes = setOf(
    "about",
    "blob",
    "chrome",
    "data",
    "file",
    "http",
    "https",
    "jar",
    "javascript",
    "moz-extension",
    "resource",
    "view-source",
)
