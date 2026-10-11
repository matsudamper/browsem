package net.matsudamper.browser

import org.mozilla.geckoview.GeckoSession

internal fun contextMenuStateForLongPress(
    linkUri: String?,
    srcUri: String?,
    elementType: Int,
    linkText: String?,
): BrowserTabScreenState.ContextMenuState? {
    val visibleLinkText = linkText?.takeIf { it.isNotBlank() }
    val isImage = elementType == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE
    return when {
        linkUri != null && isImage && srcUri != null ->
            BrowserTabScreenState.ContextMenuState.LinkWithImage(
                url = linkUri,
                imageSrcUrl = srcUri,
                linkText = visibleLinkText,
            )

        linkUri != null ->
            BrowserTabScreenState.ContextMenuState.Link(
                url = linkUri,
                linkText = visibleLinkText,
            )

        isImage && srcUri != null ->
            BrowserTabScreenState.ContextMenuState.Image(srcUrl = srcUri)

        // AUDIO / VIDEO / NONE は未対応
        else -> null
    }
}
