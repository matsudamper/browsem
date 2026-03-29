package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.geckoview.WebRequestError

class PageLoadErrorTest {

    @Test
    fun unknownHostIsMappedToAddressNotFoundMessage() {
        val error = WebRequestError(
            WebRequestError.ERROR_UNKNOWN_HOST,
            WebRequestError.ERROR_CATEGORY_URI,
        )

        val pageLoadError = error.toPageLoadError("https://example.invalid")

        assertEquals("アドレスが見つかりません", pageLoadError.title)
        assertEquals(
            "サーバー名を確認できませんでした。URL が正しいか、通信状態を確認してください。",
            pageLoadError.message,
        )
        assertEquals("https://example.invalid", pageLoadError.failingUrl)
    }

    @Test
    fun tlsErrorsAreMappedToSecureConnectionMessage() {
        val error = WebRequestError(
            WebRequestError.ERROR_SECURITY_BAD_CERT,
            WebRequestError.ERROR_CATEGORY_SECURITY,
        )

        val pageLoadError = error.toPageLoadError("https://expired.example.com")

        assertEquals("安全な接続を確立できません", pageLoadError.title)
        assertEquals(
            "証明書または TLS の問題により、このページを安全に開けませんでした。",
            pageLoadError.message,
        )
    }

    @Test
    fun unknownNetworkErrorFallsBackToGenericNetworkMessage() {
        val error = WebRequestError(
            WebRequestError.ERROR_UNKNOWN,
            WebRequestError.ERROR_CATEGORY_NETWORK,
        )

        val pageLoadError = error.toPageLoadError("https://example.com")

        assertEquals("ページを表示できません", pageLoadError.title)
        assertEquals(
            "通信に失敗しました。時間をおいて再読み込みしてください。",
            pageLoadError.message,
        )
    }
}
