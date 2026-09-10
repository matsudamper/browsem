package net.matsudamper.browser

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.browser.customtabs.CustomTabsSessionToken
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.matsudamper.browser.feature.webauthncompat.WebAuthnCompatWebExtension
import org.koin.core.context.GlobalContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

object CustomTabsWarmupStore {
    private const val MAX_SESSION_ENTRIES = 8
    private const val STALE_ENTRY_MS = 10 * 60 * 1000L

    private val lock = Any()
    private val entries = linkedMapOf<CustomTabsSessionToken, Entry>()

    private data class Entry(
        var preparedSession: GeckoSession? = null,
        var preparedUrl: String? = null,
        var updatedAt: Long = System.currentTimeMillis(),
    )

    fun onWarmup() {
        GlobalContext.get().get<GeckoRuntime>()
    }

    fun onNewSession(token: CustomTabsSessionToken) {
        synchronized(lock) {
            cleanupLocked()
            ensureEntryLocked(token).updatedAt = System.currentTimeMillis()
        }
    }

    fun onMayLaunchUrl(
        token: CustomTabsSessionToken,
        url: Uri?,
    ) {
        val targetUrl = url?.toString()?.takeIf { it.isNotBlank() } ?: return
        val (runtime, webAuthnCompatWebExtension) = runOnMainThreadBlocking {
            val koin = GlobalContext.get()
            val resolvedRuntime = koin.get<GeckoRuntime>()
            synchronized(lock) {
                cleanupLocked()
                ensureEntryLocked(token).apply {
                    preparedUrl = targetUrl
                    updatedAt = System.currentTimeMillis()
                }
            }
            resolvedRuntime to koin.get<WebAuthnCompatWebExtension>()
        }
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
        val prepared = synchronized(lock) {
            cleanupLocked()
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
            session to url
        }
        val session = prepared.first
        val preparedUrl = prepared.second
        if (launchUrl.isNotBlank() && launchUrl != preparedUrl) {
            runOnMainThreadBlocking {
                session.loadUri(launchUrl)
            }
        }
        return session
    }

    fun onSessionCleanup(token: CustomTabsSessionToken) {
        val removed = synchronized(lock) {
            val entry = entries.remove(token) ?: return
            entry.preparedSession
        }
        if (removed != null) {
            runOnMainThreadBlocking {
                removed.close()
            }
        }
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
            val session = synchronized(lock) {
                cleanupLocked()
                val entry = entries[token] ?: return@synchronized null
                if (entry.preparedUrl != targetUrl) return@synchronized null
                entry.updatedAt = System.currentTimeMillis()
                entry.preparedSession ?: GeckoSession().also { newSession ->
                    newSession.open(runtime)
                    registerBrowserSessionForRuntimeUpdates(newSession)
                    entry.preparedSession = newSession
                }
            } ?: return@runOnMainThreadBlocking
            session.loadUri(targetUrl)
        }
    }

    private fun ensureEntryLocked(token: CustomTabsSessionToken): Entry {
        return entries.getOrPut(token) {
            if (entries.size >= MAX_SESSION_ENTRIES) {
                val oldest = entries.entries.firstOrNull()
                if (oldest != null) {
                    oldest.value.preparedSession?.close()
                    entries.remove(oldest.key)
                }
            }
            Entry()
        }
    }

    private fun removeEntryIfEmptyLocked(token: CustomTabsSessionToken) {
        val entry = entries[token] ?: return
        if (entry.preparedSession == null) {
            entries.remove(token)
        }
    }

    private fun cleanupLocked() {
        val now = System.currentTimeMillis()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (_, entry) = iterator.next()
            if (now - entry.updatedAt > STALE_ENTRY_MS) {
                runOnMainThreadBlocking {
                    entry.preparedSession?.close()
                }
                iterator.remove()
            }
        }
    }

    private fun <T> runOnMainThreadBlocking(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        Handler(Looper.getMainLooper()).post {
            result = runCatching { block() }
            latch.countDown()
        }
        check(latch.await(10, TimeUnit.SECONDS)) {
            "CustomTabsWarmupStore main thread operation timed out."
        }
        return result!!.getOrThrow()
    }
}
