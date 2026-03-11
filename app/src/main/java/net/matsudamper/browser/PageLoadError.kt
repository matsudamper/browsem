package net.matsudamper.browser

import org.mozilla.geckoview.WebRequestError

internal data class PageLoadError(
    val title: String,
    val message: String,
    val failingUrl: String,
)

internal fun WebRequestError.toPageLoadError(failingUrl: String?): PageLoadError {
    val url = failingUrl.orEmpty()
    return when (code) {
        WebRequestError.ERROR_UNKNOWN_HOST -> {
            PageLoadError(
                title = "アドレスが見つかりません",
                message = "サーバー名を確認できませんでした。URL が正しいか、通信状態を確認してください。",
                failingUrl = url,
            )
        }

        WebRequestError.ERROR_CONNECTION_REFUSED,
        WebRequestError.ERROR_PROXY_CONNECTION_REFUSED,
        -> {
            PageLoadError(
                title = "接続できません",
                message = "接続先が応答を拒否しました。時間をおいて再読み込みしてください。",
                failingUrl = url,
            )
        }

        WebRequestError.ERROR_NET_TIMEOUT -> {
            PageLoadError(
                title = "応答がありません",
                message = "ページの読み込みが時間内に完了しませんでした。通信状態を確認して再読み込みしてください。",
                failingUrl = url,
            )
        }

        WebRequestError.ERROR_OFFLINE -> {
            PageLoadError(
                title = "オフラインです",
                message = "インターネット接続がありません。接続後に再読み込みしてください。",
                failingUrl = url,
            )
        }

        WebRequestError.ERROR_SECURITY_SSL,
        WebRequestError.ERROR_SECURITY_BAD_CERT,
        WebRequestError.ERROR_BAD_HSTS_CERT,
        -> {
            PageLoadError(
                title = "安全な接続を確立できません",
                message = "証明書または TLS の問題により、このページを安全に開けませんでした。",
                failingUrl = url,
            )
        }

        WebRequestError.ERROR_HTTPS_ONLY -> {
            PageLoadError(
                title = "HTTPS 接続が必要です",
                message = "このサイトは安全な HTTPS 接続のみ許可されています。",
                failingUrl = url,
            )
        }

        WebRequestError.ERROR_MALFORMED_URI,
        WebRequestError.ERROR_UNKNOWN_PROTOCOL,
        WebRequestError.ERROR_PORT_BLOCKED,
        WebRequestError.ERROR_DATA_URI_TOO_LONG,
        -> {
            PageLoadError(
                title = "このアドレスは開けません",
                message = "入力したアドレスの形式を確認してください。",
                failingUrl = url,
            )
        }

        WebRequestError.ERROR_FILE_NOT_FOUND,
        WebRequestError.ERROR_FILE_ACCESS_DENIED,
        -> {
            PageLoadError(
                title = "ファイルを開けません",
                message = "ファイルが見つからないか、アクセス権限がありません。",
                failingUrl = url,
            )
        }

        WebRequestError.ERROR_INVALID_CONTENT_ENCODING,
        WebRequestError.ERROR_CORRUPTED_CONTENT,
        WebRequestError.ERROR_UNSAFE_CONTENT_TYPE,
        WebRequestError.ERROR_CONTENT_CRASHED,
        -> {
            PageLoadError(
                title = "ページを表示できません",
                message = "受信したデータを正しく表示できませんでした。再読み込みしても改善しない場合があります。",
                failingUrl = url,
            )
        }

        else -> {
            PageLoadError(
                title = "ページを表示できません",
                message = defaultPageLoadErrorMessage(),
                failingUrl = url,
            )
        }
    }
}

private fun WebRequestError.defaultPageLoadErrorMessage(): String {
    return when (category) {
        WebRequestError.ERROR_CATEGORY_SECURITY -> {
            "接続の安全性を確認できませんでした。"
        }

        WebRequestError.ERROR_CATEGORY_NETWORK,
        WebRequestError.ERROR_CATEGORY_PROXY,
        -> {
            "通信に失敗しました。時間をおいて再読み込みしてください。"
        }

        WebRequestError.ERROR_CATEGORY_URI -> {
            "入力したアドレスを開けませんでした。"
        }

        WebRequestError.ERROR_CATEGORY_CONTENT -> {
            "ページの内容を正しく処理できませんでした。"
        }

        WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> {
            "安全性の理由により、このページは表示できません。"
        }

        else -> {
            "しばらくしてから再読み込みしてください。"
        }
    }
}
