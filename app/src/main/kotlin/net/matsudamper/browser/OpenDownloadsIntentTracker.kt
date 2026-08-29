package net.matsudamper.browser

/**
 * ダウンロード通知タップ Intent の重複処理を防ぐための識別子管理。
 * プロセスキル後の Activity 復元で同じ Intent が再配信された場合に、
 * 遷移アニメーションやハイライトの再実行を防ぐ。
 */
internal object OpenDownloadsIntentTracker {
    fun intentKey(workerId: String?): String = workerId ?: ""

    fun shouldProcess(workerId: String?, lastProcessedKey: String?): Boolean {
        return intentKey(workerId) != lastProcessedKey
    }
}
