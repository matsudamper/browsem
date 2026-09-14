package net.matsudamper.browser

import org.junit.Assert.assertEquals
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

    @Test
    fun `http と https のみ http スキームと判定される`() {
        assertTrue(isHttpUri("https://example.com/"))
        assertTrue(isHttpUri("HTTP://example.com/"))
        assertFalse(isHttpUri("okta-verify://enroll"))
        assertFalse(isHttpUri("intent://example.com/#Intent;scheme=https;end"))
        assertFalse(isHttpUri("example.com"))
    }

    @Test
    fun `リダイレクトでない遷移は App Links 判定を行う`() {
        assertTrue(
            shouldCheckExternalAppForNavigation(
                uri = "https://example.com/",
                isRedirect = false,
            ),
        )
    }

    @Test
    fun `リダイレクト先は App Links 判定を行わない`() {
        assertFalse(
            shouldCheckExternalAppForNavigation(
                uri = "https://example.com/",
                isRedirect = true,
            ),
        )
    }

    @Test
    fun `ログ用 URL は scheme とホストだけになる`() {
        assertEquals(
            "https://example.okta.com",
            redactUrlForLog("https://example.okta.com/app/github/abc/sso/saml?SAMLRequest=secret#token=1"),
        )
        assertEquals("https://example.com", redactUrlForLog("https://user:pass@example.com/path"))
        assertEquals("okta-verify:", redactUrlForLog("okta-verify:token"))
        assertEquals("(スキームなし)", redactUrlForLog(""))
    }

    @Test
    fun `独自スキームはリダイレクト先でも外部アプリ判定を行う`() {
        assertTrue(
            shouldCheckExternalAppForNavigation(
                uri = "okta-verify://enroll",
                isRedirect = true,
            ),
        )
    }
}
