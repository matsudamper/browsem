package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mozilla.geckoview.GeckoSession

class ContextMenuContentTest {

    @Test
    fun `リンクのテキストがあればコピー対象にする`() {
        val state = contextMenuStateForLongPress(
            linkUri = "https://example.com",
            srcUri = null,
            elementType = GeckoSession.ContentDelegate.ContextElement.TYPE_NONE,
            linkText = "Example",
        )

        assertEquals(
            BrowserTabScreenState.ContextMenuState.Link(
                url = "https://example.com",
                linkText = "Example",
            ),
            state,
        )
    }

    @Test
    fun `空白だけのリンクテキストはコピー対象にしない`() {
        val state = contextMenuStateForLongPress(
            linkUri = "https://example.com",
            srcUri = null,
            elementType = GeckoSession.ContentDelegate.ContextElement.TYPE_NONE,
            linkText = " \n",
        )

        assertEquals(
            BrowserTabScreenState.ContextMenuState.Link(
                url = "https://example.com",
                linkText = null,
            ),
            state,
        )
    }

    @Test
    fun `画像リンクは画像URLとリンクテキストを持つ`() {
        val state = contextMenuStateForLongPress(
            linkUri = "https://example.com/page",
            srcUri = "https://example.com/image.png",
            elementType = GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE,
            linkText = "図の説明",
        )

        assertEquals(
            BrowserTabScreenState.ContextMenuState.LinkWithImage(
                url = "https://example.com/page",
                imageSrcUrl = "https://example.com/image.png",
                linkText = "図の説明",
            ),
            state,
        )
    }

    @Test
    fun `画像だけならリンクテキストを持たない`() {
        val state = contextMenuStateForLongPress(
            linkUri = null,
            srcUri = "https://example.com/image.png",
            elementType = GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE,
            linkText = "使わないテキスト",
        )

        assertEquals(
            BrowserTabScreenState.ContextMenuState.Image(
                srcUrl = "https://example.com/image.png",
            ),
            state,
        )
    }

    @Test
    fun `リンクも画像もなければメニューを出さない`() {
        val state = contextMenuStateForLongPress(
            linkUri = null,
            srcUri = null,
            elementType = GeckoSession.ContentDelegate.ContextElement.TYPE_NONE,
            linkText = "テキスト",
        )

        assertNull(state)
    }
}
