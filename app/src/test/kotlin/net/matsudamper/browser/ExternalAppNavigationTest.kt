package net.matsudamper.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAppNavigationTest {

    @Test
    fun `Google Pay のホストはブラウザ内処理と判定される`() {
        assertTrue(isBrowserPinnedHost("pay.google.com"))
        assertTrue(isBrowserPinnedHost("pay.sandbox.google.com"))
        assertTrue(isBrowserPinnedHost("payments.google.com"))
        assertTrue(isBrowserPinnedHost("accounts.google.com"))
    }

    @Test
    fun `大文字を含むホストも判定される`() {
        assertTrue(isBrowserPinnedHost("Pay.Google.Com"))
    }

    @Test
    fun `サブドメインも判定される`() {
        assertTrue(isBrowserPinnedHost("jp.payments.google.com"))
    }

    @Test
    fun `対象外のホストはブラウザ内処理と判定されない`() {
        assertFalse(isBrowserPinnedHost(null))
        assertFalse(isBrowserPinnedHost("google.com"))
        assertFalse(isBrowserPinnedHost("play.google.com"))
        assertFalse(isBrowserPinnedHost("example.com"))
        // 前方一致や部分一致で誤判定しないこと
        assertFalse(isBrowserPinnedHost("fakepay.google.com.example.com"))
        assertFalse(isBrowserPinnedHost("notpay.google.com.evil.test"))
    }
}
