package net.matsudamper.browser

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WebShareFilesTest {
    @Test
    fun buildWebShareFilesIntent_singleFile_setsStreamAndText() {
        val uri = Uri.parse("content://example/photo.png")
        val intent = buildWebShareFilesIntent(
            title = "画像",
            text = "説明",
            url = "https://example.com",
            files = listOf(
                WebShareFilePayload(
                    name = "photo.png",
                    mimeType = "image/png",
                    bytes = byteArrayOf(1, 2, 3),
                ),
            ),
            uris = listOf(uri),
        )

        assertNotNull(intent)
        assertEquals(Intent.ACTION_SEND, intent!!.action)
        assertEquals("image/png", intent.type)
        assertEquals("説明\nhttps://example.com", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("画像", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM))
    }

    @Test
    fun buildWebShareFilesIntent_multipleFiles_usesSendMultiple() {
        val uris = listOf(
            Uri.parse("content://example/a.png"),
            Uri.parse("content://example/b.png"),
        )
        val intent = buildWebShareFilesIntent(
            title = null,
            text = null,
            url = null,
            files = listOf(
                WebShareFilePayload(
                    name = "a.png",
                    mimeType = "image/png",
                    bytes = byteArrayOf(1),
                ),
                WebShareFilePayload(
                    name = "b.png",
                    mimeType = "image/png",
                    bytes = byteArrayOf(2),
                ),
            ),
            uris = uris,
        )

        assertNotNull(intent)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent!!.action)
        assertEquals("image/png", intent.type)
        assertEquals(uris, intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
    }

    @Test
    fun buildWebShareFilesIntent_returnsNullWhenUriCountMismatch() {
        val intent = buildWebShareFilesIntent(
            title = null,
            text = null,
            url = null,
            files = listOf(
                WebShareFilePayload(
                    name = "a.png",
                    mimeType = "image/png",
                    bytes = byteArrayOf(1),
                ),
            ),
            uris = emptyList(),
        )

        assertNull(intent)
    }
}
