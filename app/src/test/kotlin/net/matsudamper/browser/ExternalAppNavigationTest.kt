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

    @Test
    fun `http と https のみ http スキームと判定される`() {
        assertTrue(isHttpUri("https://example.com/"))
        assertTrue(isHttpUri("HTTP://example.com/"))
        assertFalse(isHttpUri("okta-verify://enroll"))
        assertFalse(isHttpUri("intent://example.com/#Intent;scheme=https;end"))
        assertFalse(isHttpUri("example.com"))
    }

    @Test
    fun `ユーザー操作の遷移は App Links 判定を行う`() {
        assertTrue(
            shouldCheckExternalAppForNavigation(
                uri = "https://example.com/",
                hasUserGesture = true,
                isDirectNavigation = false,
            ),
        )
    }

    @Test
    fun `アプリが発行したロードは App Links 判定を行う`() {
        assertTrue(
            shouldCheckExternalAppForNavigation(
                uri = "https://example.com/",
                hasUserGesture = false,
                isDirectNavigation = true,
            ),
        )
    }

    @Test
    fun `自動リダイレクトは App Links 判定を行わない`() {
        assertFalse(
            shouldCheckExternalAppForNavigation(
                uri = "https://example.com/",
                hasUserGesture = false,
                isDirectNavigation = false,
            ),
        )
    }

    @Test
    fun `独自スキームは自動遷移でも外部アプリ判定を行う`() {
        assertTrue(
            shouldCheckExternalAppForNavigation(
                uri = "okta-verify://enroll",
                hasUserGesture = false,
                isDirectNavigation = false,
            ),
        )
    }
}
