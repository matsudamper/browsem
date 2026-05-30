package net.matsudamper.browser.download

import java.util.concurrent.ConcurrentHashMap
import org.mozilla.geckoview.WebResponse

/**
 * GeckoViewが onExternalResponse で受け取った WebResponse（実データ入りのボディ）を、
 * WorkManager の Worker へ同一プロセス内で引き渡すための一時保管庫。
 *
 * パスワード submit(POST) のレスポンス・ワンタイムURL・セッション/トークン依存・blob などの
 * ダウンロードは、URLを GET で再取得しても元のデータが取得できず 0 バイトになる。
 * そのため元レスポンスのボディを保持し、Worker がそれを直接保存できるようにする。
 *
 * WorkManager の inputData には InputStream を渡せないため、workId をキーにプロセス内で受け渡す。
 * プロセスが再起動した場合はここから取得できず、Worker 側は URL 再取得にフォールバックする。
 */
object PendingDownloadBodyStore {
    private val responses = ConcurrentHashMap<String, WebResponse>()

    /** 指定 workId に対応するボディ付きレスポンスを保持する */
    fun put(workId: String, response: WebResponse) {
        responses[workId] = response
    }

    /**
     * 指定 workId のレスポンスを取り出して保管庫から削除する。
     * 取り出した側がボディをクローズする責務を負う。存在しなければ null。
     */
    fun take(workId: String): WebResponse? {
        return responses.remove(workId)
    }

    /** 指定 workId のレスポンスを破棄し、ボディをクローズする（エンキュー失敗・キャンセル時など） */
    fun discard(workId: String) {
        responses.remove(workId)?.body?.close()
    }
}
