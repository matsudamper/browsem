package net.matsudamper.browser

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.browser.customtabs.CustomTabsSessionToken
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.matsudamper.browser.feature.webauthncompat.WebAuthnCompatWebExtension
import org.koin.core.context.GlobalContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

object CustomTabsWarmupStore {
    private const val MAX_SESSION_ENTRIES = 8
    private const val STALE_ENTRY_MS = 10 * 60 * 1000L

    // カスタムタブのウォームアップはプロセス生存中いつでも来るため、プロセスと同じ寿命で持つ。
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val lock = Any()
    private val entries = linkedMapOf<CustomTabsSessionToken, Entry>()

    private data class Entry(
        var preparedSession: GeckoSession? = null,
        var preparedUrl: String? = null,
        var updatedAt: Long = System.currentTimeMillis(),
    )

    fun onWarmup() {
        // Binder スレッドから呼ばれる。GeckoRuntime の初期化はメインスレッドで行う必要がある。
        warmupScope.launch {
            GlobalContext.get().get<GeckoRuntimeInitializer>().initialize()
        }
    }

    fun onNewSession(token: CustomTabsSessionToken) {
        val closableSessions = mutableListOf<GeckoSession>()
        synchronized(lock) {
            closableSessions += takeStaleSessionsLocked()
            ensureEntryLocked(token, closableSessions).updatedAt = System.currentTimeMillis()
        }
        closeOnMainThread(closableSessions)
    }

    fun onMayLaunchUrl(
        token: CustomTabsSessionToken,
        url: Uri?,
    ) {
        val targetUrl = url?.toString()?.takeIf { it.isNotBlank() } ?: return
        // 起動・切断が後から来ても要求の記録が後追いにならないよう、エントリの更新は同期的に行う。
        // GeckoSession を閉じるのはメインスレッドに限られるため、取り外して後段へ渡す。
        val closableSessions = mutableListOf<GeckoSession>()
        synchronized(lock) {
            closableSessions += takeStaleSessionsLocked()
            ensureEntryLocked(token, closableSessions).apply {
                preparedSession?.takeIf { preparedUrl != targetUrl }?.let { staleSession ->
                    closableSessions.add(staleSession)
                    preparedSession = null
                }
                preparedUrl = targetUrl
                updatedAt = System.currentTimeMillis()
            }
        }
        // Binder スレッドから呼ばれる。GeckoRuntime の初期化と GeckoSession の操作はメインスレッドで行う。
        warmupScope.launch {
            closableSessions.forEach { it.close() }
            val koin = GlobalContext.get()
            val runtime = koin.get<GeckoRuntimeInitializer>().initialize()
            installWebAuthnCompatAndPrepare(
                token = token,
                targetUrl = targetUrl,
                runtime = runtime,
                webAuthnCompatWebExtension = koin.get<WebAuthnCompatWebExtension>(),
            )
        }
    }

    private fun installWebAuthnCompatAndPrepare(
        token: CustomTabsSessionToken,
        targetUrl: String,
        runtime: GeckoRuntime,
        webAuthnCompatWebExtension: WebAuthnCompatWebExtension,
    ) {
        webAuthnCompatWebExtension.install(runtime).accept(
            {
                prepareSessionIfCurrent(token, targetUrl, runtime)
            },
            { firstError ->
                Log.w("CustomTabsWarmupStore", "WebAuthn 互換設定の反映待機に失敗。再試行します", firstError)
                webAuthnCompatWebExtension.retryInstall(runtime).accept(
                    {
                        prepareSessionIfCurrent(token, targetUrl, runtime)
                    },
                    { retryError ->
                        Log.w(
                            "CustomTabsWarmupStore",
                            "WebAuthn 互換設定の再試行に失敗したためプリウォームを中止します",
                            retryError,
                        )
                    },
                )
            },
        )
    }

    fun consumePreparedSession(
        token: CustomTabsSessionToken,
        launchUrl: String,
    ): GeckoSession? {
        val closableSessions = mutableListOf<GeckoSession>()
        val prepared = synchronized(lock) {
            closableSessions += takeStaleSessionsLocked()
            takePreparedSessionLocked(token)
        }
        closeOnMainThread(closableSessions)
        val (session, preparedUrl) = prepared ?: return null
        if (launchUrl.isNotBlank() && launchUrl != preparedUrl) {
            runOnMainThreadBlocking {
                session.loadUri(launchUrl)
            }
        }
        return session
    }

    private fun takePreparedSessionLocked(token: CustomTabsSessionToken): Pair<GeckoSession, String?>? {
        val entry = entries[token] ?: return null
        entry.updatedAt = System.currentTimeMillis()
        val session = entry.preparedSession
        if (session == null) {
            entries.remove(token)
            return null
        }
        val url = entry.preparedUrl
        entry.preparedSession = null
        entry.preparedUrl = null
        removeEntryIfEmptyLocked(token)
        return session to url
    }

    fun onSessionCleanup(token: CustomTabsSessionToken) {
        val removed = synchronized(lock) {
            entries.remove(token)?.preparedSession
        }
        closeOnMainThread(listOfNotNull(removed))
    }

    @VisibleForTesting
    fun hasPreparedSessionForTesting(token: CustomTabsSessionToken, url: String): Boolean {
        return synchronized(lock) {
            val entry = entries[token] ?: return false
            entry.preparedSession != null && entry.preparedUrl == url
        }
    }

    @VisibleForTesting
    fun resetForTesting() {
        val sessions = synchronized(lock) {
            val allSessions = entries.values.mapNotNull { it.preparedSession }
            entries.clear()
            allSessions
        }
        sessions.forEach { session ->
            runOnMainThreadBlocking {
                runCatching { session.close() }
            }
        }
    }

    private fun prepareSessionIfCurrent(
        token: CustomTabsSessionToken,
        targetUrl: String,
        runtime: GeckoRuntime,
    ) {
        runOnMainThreadBlocking {
            val closableSessions = mutableListOf<GeckoSession>()
            val session = synchronized(lock) {
                closableSessions += takeStaleSessionsLocked()
                prepareSessionLocked(token, targetUrl, runtime)
            }
            closableSessions.forEach { it.close() }
            session?.loadUri(targetUrl)
        }
    }

    private fun prepareSessionLocked(
        token: CustomTabsSessionToken,
        targetUrl: String,
        runtime: GeckoRuntime,
    ): GeckoSession? {
        val entry = entries[token] ?: return null
        if (entry.preparedUrl != targetUrl) return null
        entry.updatedAt = System.currentTimeMillis()
        return entry.preparedSession ?: GeckoSession().also { newSession ->
            newSession.open(runtime)
            registerBrowserSessionForRuntimeUpdates(newSession)
            entry.preparedSession = newSession
        }
    }

    /** 容量超過で退避したセッションは [evictedSessions] へ移し、呼び出し側がメインスレッドで閉じる。 */
    private fun ensureEntryLocked(
        token: CustomTabsSessionToken,
        evictedSessions: MutableList<GeckoSession>,
    ): Entry {
        return entries.getOrPut(token) {
            if (entries.size >= MAX_SESSION_ENTRIES) {
                val oldest = entries.entries.firstOrNull()
                if (oldest != null) {
                    oldest.value.preparedSession?.let { evictedSessions.add(it) }
                    entries.remove(oldest.key)
                }
            }
            Entry()
        }
    }

    private fun closeOnMainThread(sessions: List<GeckoSession>) {
        if (sessions.isEmpty()) return
        warmupScope.launch {
            sessions.forEach { it.close() }
        }
    }

    private fun removeEntryIfEmptyLocked(token: CustomTabsSessionToken) {
        val entry = entries[token] ?: return
        if (entry.preparedSession == null) {
            entries.remove(token)
        }
    }

    /**
     * 期限切れエントリを外し、閉じるべきセッションを返す。
     * lock 保持中にメインスレッドを待つと Binder スレッドとの間でロック順序が反転するため、閉じるのは呼び出し側に任せる。
     */
    private fun takeStaleSessionsLocked(): List<GeckoSession> {
        val now = System.currentTimeMillis()
        val staleSessions = mutableListOf<GeckoSession>()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (_, entry) = iterator.next()
            if (now - entry.updatedAt > STALE_ENTRY_MS) {
                entry.preparedSession?.let { staleSessions.add(it) }
                iterator.remove()
            }
        }
        return staleSessions
    }

    private fun <T> runOnMainThreadBlocking(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<T>>()
        Handler(Looper.getMainLooper()).post {
            result.set(runCatching { block() })
            latch.countDown()
        }
        check(latch.await(10, TimeUnit.SECONDS)) {
            "CustomTabsWarmupStore main thread operation timed out."
        }
        return result.get().getOrThrow()
    }
}
