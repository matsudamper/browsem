package net.matsudamper.browser

import android.net.Uri

internal fun resolveAmoInstallUriFromPage(pageUrl: String): String? {
    val uri = runCatching { Uri.parse(pageUrl) }.getOrNull() ?: return null
    val path = uri.path.orEmpty()
    if (path.endsWith(".xpi", ignoreCase = true)) {
        return pageUrl
    }

    val host = uri.host?.lowercase() ?: return null
    if (host != "addons.mozilla.org") {
        return null
    }

    val segments = uri.pathSegments
        ?.filter { it.isNotBlank() }
        ?: return null

    val addonIndex = segments.indexOf("addon")
    if (addonIndex == -1 || addonIndex + 1 >= segments.size) {
        return null
    }

    val slug = segments[addonIndex + 1]
    if (slug.isBlank()) {
        return null
    }

    return "https://addons.mozilla.org/firefox/downloads/latest/$slug/latest.xpi"
}
