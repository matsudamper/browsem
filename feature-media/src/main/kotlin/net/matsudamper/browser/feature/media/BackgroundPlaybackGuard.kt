package net.matsudamper.browser.feature.media

/**
 * 画面を閉じている間、通知操作以外で始まった再生を止めるための判定。
 *
 * 閉じた時点で鳴っていた再生の継続（バッファリング後の再開、画質変更）は許す。
 * ページ側で識別したメディア以外を、タイトルの一致だけで継続扱いにしない。
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

    fun canActivate(session: Session, playback: PlaybackIdentity, nowMs: Long): Boolean {
        val continuation = continuationBySession[session]
        return !inBackground || (continuation != null && isContinuation(continuation, playback)) ||
            (allowedCommandSession == session && nowMs - allowedCommandAtMs <= USER_COMMAND_WINDOW_MS)
    }

    fun onPlaybackEnded(session: Session, playbackId: String) {
        if (continuationBySession[session]?.playbackId == playbackId) {
            continuationBySession.remove(session)
        }
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
        playbackId: String,
        nowMs: Long,
    ): Boolean {
        if (!inBackground || !isPlaying) {
            return false
        }
        val current = PlaybackIdentity(mediaSrc = mediaSrc, title = title, playbackId = playbackId)
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
    val playbackId: String,
)

/**
 * 閉じた時点の再生と、いま鳴っているものが同じかを返す。
 *
 * ページ側の ID は、同じ要素で再生位置と長さを保つ画質変更でも維持される。
 * ID がない Gecko のコールバックだけの場合は、既知の同一 URL に限定する。
 */
internal fun isContinuation(previous: PlaybackIdentity, current: PlaybackIdentity): Boolean {
    if (previous.playbackId.isNotEmpty() && current.playbackId.isNotEmpty()) {
        return previous.playbackId == current.playbackId
    }
    return previous.mediaSrc.isNotEmpty() && previous.mediaSrc == current.mediaSrc
}

/** 未知だった URL やタイトルが後から埋まったかを返す。継続判定をその値で更新するために使う。 */
internal fun isMoreSpecific(current: PlaybackIdentity, previous: PlaybackIdentity): Boolean {
    val srcFilledIn = previous.mediaSrc.isEmpty() && current.mediaSrc.isNotEmpty()
    val titleFilledIn = previous.title.isEmpty() && current.title.isNotEmpty()
    val idFilledIn = previous.playbackId.isEmpty() && current.playbackId.isNotEmpty()
    return srcFilledIn || titleFilledIn || idFilledIn
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
