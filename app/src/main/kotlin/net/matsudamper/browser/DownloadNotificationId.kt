package net.matsudamper.browser

import java.util.UUID

/**
 * ダウンロード通知の ID。通知タップ用 PendingIntent の requestCode も兼ねる。
 *
 * PendingIntent の同一性判定に extras は含まれないため、requestCode と Intent の component / action が
 * 同じだと進捗通知と完了通知が同じ PendingIntent を共有し、FLAG_UPDATE_CURRENT で後から作った側の
 * extras に上書きされる。種別ごとに別の値を返してこれを防ぐ。
 */
internal object DownloadNotificationId {
    /** 進捗通知。Worker のフォアグラウンド通知と、Worker 起動前の通知で共有する */
    fun progress(currentWorkerId: UUID): Int = positiveHash(currentWorkerId)

    fun complete(currentWorkerId: UUID): Int = COMPLETE_BASE + positiveHash(currentWorkerId)

    fun failure(currentWorkerId: UUID): Int = FAILURE_BASE + positiveHash(currentWorkerId)

    fun cancelled(currentWorkerId: UUID): Int = CANCELLED_BASE + positiveHash(currentWorkerId)

    /** 負の hashCode による通知 ID 衝突を防ぐため、非負の値に変換する */
    private fun positiveHash(currentWorkerId: UUID): Int = currentWorkerId.hashCode() and 0x7fffffff

    private const val COMPLETE_BASE = 10000
    private const val FAILURE_BASE = 20000
    private const val CANCELLED_BASE = 30000
}
