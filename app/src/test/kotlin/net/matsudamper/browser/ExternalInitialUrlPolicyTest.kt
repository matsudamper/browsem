package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalInitialUrlPolicyTest {

    @Test
    fun `http と https はそのまま通す`() {
        assertEquals("http://example.com/", ExternalInitialUrlPolicy.sanitize("http://example.com/"))
        assertEquals("https://example.com/", ExternalInitialUrlPolicy.sanitize("https://example.com/"))
    }

    @Test
    fun `スキームの大文字小文字は区別しない`() {
        assertEquals("HTTPS://example.com/", ExternalInitialUrlPolicy.sanitize("HTTPS://example.com/"))
    }

    @Test
    fun `ローカルファイルや外部コンテンツのスキームは弾く`() {
        assertNull(ExternalInitialUrlPolicy.sanitize("file:///storage/emulated/0/Download/attack.html"))
        assertNull(ExternalInitialUrlPolicy.sanitize("content://com.example.attacker/page.html"))
        assertNull(ExternalInitialUrlPolicy.sanitize("javascript:alert(1)"))
        assertNull(ExternalInitialUrlPolicy.sanitize("intent://example.com#Intent;scheme=https;end"))
    }

    @Test
    fun `スキームを持たない文字列と null は弾く`() {
        assertNull(ExternalInitialUrlPolicy.sanitize("//example.com/"))
        assertNull(ExternalInitialUrlPolicy.sanitize(null))
    }
}
