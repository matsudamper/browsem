package net.matsudamper.browser.feature.media

/**
 * 画面を閉じている間、通知操作以外で始まった再生を止めるための判定。
 *
 * 閉じた時点で鳴っていた再生の継続（バッファリング後の再開、画質変更）は許す。
 * URL とタイトルが両方変わった再生は、ページが次の動画を始めたものとして止める。
 */
internal class BackgroundPlaybackGuard<Session> {
    private var inBackground = false
    private val continuationBySession = mutableMapOf<Session, PlaybackIdentity>()
    private var allowedCommandSession: Session? = null
    private var allowedCommandAtMs = 0L

    fun onEnteredBackground(playingBySession: Map<Session, PlaybackIdentity>) {
        inBackground = true
        continuationBySession.clear()
        continuationBySession.putAll(playingBySession)
        allowedCommandSession = null
    }

    fun onEnteredForeground() {
        inBackground = false
        continuationBySession.clear()
        allowedCommandSession = null
    }

    fun onUserTransportCommand(session: Session, nowMs: Long) {
        allowedCommandSession = session
        allowedCommandAtMs = nowMs
    }

    /**
     * 通知の一時停止・停止、またはメディアの終了。
     * 同じ URL のまま後から play() されても、閉じていたあいだの継続とは扱わない。
     */
    fun onExplicitStop(session: Session) {
        continuationBySession.remove(session)
        if (allowedCommandSession == session) {
            allowedCommandSession = null
        }
    }

    fun isInBackground(): Boolean = inBackground

    fun canActivate(session: Session, nowMs: Long): Boolean {
        return !inBackground || continuationBySession.containsKey(session) ||
            (allowedCommandSession == session && nowMs - allowedCommandAtMs <= USER_COMMAND_WINDOW_MS)
    }

    fun forget(session: Session) {
        continuationBySession.remove(session)
        if (allowedCommandSession == session) {
            allowedCommandSession = null
        }
    }

    /**
     * この再生を pause すべきなら true。
     *
     * 通知の次へはページが次のメディアを始めるまで数秒かかることがある。
     * その間に届く、閉じた時点と同じ再生の snapshot ではコマンドを消費しない。
     * 押したあとの勝手な再生まで許さないよう、コマンドは [USER_COMMAND_WINDOW_MS] で失効する。
     */
    fun shouldPause(
        session: Session,
        isPlaying: Boolean,
        mediaSrc: String,
        title: String,
        nowMs: Long,
    ): Boolean {
        if (!inBackground || !isPlaying) {
            return false
        }
        val current = PlaybackIdentity(mediaSrc = mediaSrc, title = title)
        val continuation = continuationBySession[session]
        if (continuation != null && isContinuation(continuation, current)) {
            if (isMoreSpecific(current, continuation)) {
                continuationBySession[session] = current
            }
            return false
        }
        val commandSession = allowedCommandSession
        val commandIsFresh = commandSession == session &&
            nowMs - allowedCommandAtMs <= USER_COMMAND_WINDOW_MS
        if (commandIsFresh) {
            allowedCommandSession = null
            continuationBySession[session] = current
            return false
        }
        if (commandSession == session) {
            allowedCommandSession = null
        }
        return true
    }

    companion object {
        const val USER_COMMAND_WINDOW_MS = 30_000L
    }
}

internal data class PlaybackIdentity(
    val mediaSrc: String,
    val title: String,
)

/**
 * 閉じた時点の再生と、いま鳴っているものが同じかを返す。
 *
 * 同じ要素のまま配信 URL だけ変わる画質の切り替わりと、タイトルだけの更新は継続。
 * URL とタイトルが両方変わったときは次の動画なので継続ではない。
 * どちらかが未知のときは、鳴っている音を誤って切らないよう継続とみなす。
 */
internal fun isContinuation(previous: PlaybackIdentity, current: PlaybackIdentity): Boolean {
    if (previous.mediaSrc.isNotEmpty() && previous.mediaSrc == current.mediaSrc) {
        return true
    }
    if (previous.title.isNotEmpty() && previous.title == current.title) {
        return true
    }
    val srcDisagrees = previous.mediaSrc.isNotEmpty() &&
        current.mediaSrc.isNotEmpty() &&
        previous.mediaSrc != current.mediaSrc
    val titleDisagrees = previous.title.isNotEmpty() &&
        current.title.isNotEmpty() &&
        previous.title != current.title
    return !srcDisagrees || !titleDisagrees
}

/** 未知だった URL やタイトルが後から埋まったかを返す。継続判定をその値で更新するために使う。 */
internal fun isMoreSpecific(current: PlaybackIdentity, previous: PlaybackIdentity): Boolean {
    val srcFilledIn = previous.mediaSrc.isEmpty() && current.mediaSrc.isNotEmpty()
    val titleFilledIn = previous.title.isEmpty() && current.title.isNotEmpty()
    return srcFilledIn || titleFilledIn
}

/**
 * 勝手な再生を止めたことを、連続した再試行では一度だけ知らせる。
 * 画面オフ中の Toast は見えないことがあるため、前面に戻ったときにもう一度出す。
 */
internal class BlockedPlaybackNotice(
    private val repeatIntervalMs: Long,
) {
    private var pendingForegroundNotice = false
    private var hasShown = false
    private var lastShownAtMs = 0L

    /** 今 Toast を出すべきなら true。 */
    fun onBlocked(nowMs: Long): Boolean {
        pendingForegroundNotice = true
        return showIfDue(nowMs)
    }

    /** 前面に戻ったとき、未表示の停止があれば Toast を出すべきなら true。 */
    fun onEnteredForeground(nowMs: Long): Boolean {
        if (!pendingForegroundNotice) {
            return false
        }
        pendingForegroundNotice = false
        return showIfDue(nowMs)
    }

    private fun showIfDue(nowMs: Long): Boolean {
        if (hasShown && nowMs - lastShownAtMs < repeatIntervalMs) {
            return false
        }
        hasShown = true
        lastShownAtMs = nowMs
        return true
    }
}
