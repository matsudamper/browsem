package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalInitialUrlTest {

    @Test
    fun `http と https はそのまま通す`() {
        assertEquals("http://example.com/", sanitizeExternalInitialUrl("http://example.com/"))
        assertEquals("https://example.com/", sanitizeExternalInitialUrl("https://example.com/"))
    }

    @Test
    fun `スキームの大文字小文字は区別しない`() {
        assertEquals("HTTPS://example.com/", sanitizeExternalInitialUrl("HTTPS://example.com/"))
    }

    @Test
    fun `ローカルファイルや外部コンテンツのスキームは弾く`() {
        assertNull(sanitizeExternalInitialUrl("file:///storage/emulated/0/Download/attack.html"))
        assertNull(sanitizeExternalInitialUrl("content://com.example.attacker/page.html"))
        assertNull(sanitizeExternalInitialUrl("javascript:alert(1)"))
        assertNull(sanitizeExternalInitialUrl("intent://example.com#Intent;scheme=https;end"))
    }

    @Test
    fun `スキームを持たない文字列と null は弾く`() {
        assertNull(sanitizeExternalInitialUrl("//example.com/"))
        assertNull(sanitizeExternalInitialUrl(null))
    }
}
