package net.matsudamper.browser

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
        // クエリに絶対 URL を持つ独自スキームでも、内側の :// を区切りと誤認しない
        assertEquals("myapp:", redactUrlForLog("myapp:callback?code=secret&redirect=https://example.com/"))
        assertEquals("(スキームなし)", redactUrlForLog(""))
        assertEquals("(スキームなし)", redactUrlForLog("example.com"))
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

    @Test
    fun `同じドメインへのリダイレクトと判定される`() {
        assertTrue(
            isSameDomainRedirect(
                previousUri = "https://www.amazon.co.jp/your-orders/pop?orderId=1",
                uri = "https://www.amazon.co.jp/ap/signin?openid.return_to=x",
            ),
        )
        // www. などの接頭辞は同一視する
        assertTrue(
            isSameDomainRedirect(
                previousUri = "https://m.example.com/",
                uri = "https://EXAMPLE.com/login",
            ),
        )
        // java.net.URI が拒否する | を含む URL でもホストを取れる
        assertTrue(
            isSameDomainRedirect(
                previousUri = "https://example.com/a|b",
                uri = "https://example.com/login",
            ),
        )
    }

    @Test
    fun `別ドメインへのリダイレクトは同じドメインと判定されない`() {
        assertFalse(
            isSameDomainRedirect(
                previousUri = "https://www.amazon.jp/your-orders/pop",
                uri = "https://www.amazon.co.jp/your-orders/pop",
            ),
        )
        assertFalse(
            isSameDomainRedirect(
                previousUri = "https://shop.example.com/",
                uri = "https://example.com/",
            ),
        )
    }

    @Test
    fun `リダイレクト元が無いか http 以外なら同じドメインと判定されない`() {
        assertFalse(isSameDomainRedirect(previousUri = null, uri = "https://example.com/"))
        assertFalse(isSameDomainRedirect(previousUri = "about:blank", uri = "https://example.com/"))
        assertFalse(isSameDomainRedirect(previousUri = "https://example.com/", uri = "myapp://example.com/"))
    }

    @Test
    fun `App Link はリンク先のドメインで開かないを記録する`() {
        assertEquals(
            "youtube.com",
            externalAppDeclineDomainOf(
                sourceUri = "https://m.youtube.com/watch?v=1",
                requestingPageUri = "https://example.com/",
            ),
        )
    }

    @Test
    fun `独自スキームは要求したページのドメインで開かないを記録する`() {
        assertEquals(
            "example.com",
            externalAppDeclineDomainOf(
                sourceUri = "intent://open#Intent;scheme=myapp;end",
                requestingPageUri = "https://www.example.com/page",
            ),
        )
        assertNull(
            externalAppDeclineDomainOf(
                sourceUri = "myapp://open",
                requestingPageUri = null,
            ),
        )
    }

    @Test
    fun `開かないを選んだドメインの App Link はブラウザで読み込む`() {
        val launch = launchOf(sourceUri = "https://youtube.com/watch?v=1", fallbackUrl = null)
        assertEquals(ExternalAppNavigationAction.AllowInBrowser, launch.toDeclinedAction())
    }

    @Test
    fun `開かないを選んだドメインの独自スキームは開かないを選んだときと同じ経路で扱う`() {
        val launch = launchOf(sourceUri = "intent://open#Intent;scheme=myapp;end", fallbackUrl = "https://play.google.com/store/apps/details?id=a")
        assertEquals(ExternalAppNavigationAction.Declined(launch.request), launch.toDeclinedAction())
    }

    private fun launchOf(sourceUri: String, fallbackUrl: String?): ExternalAppNavigationAction.Launch {
        return ExternalAppNavigationAction.Launch(
            PendingExternalAppLaunch(
                sourceUri = sourceUri,
                intent = Intent(),
                appName = null,
                fallbackUrl = fallbackUrl,
            ),
        )
    }
}
