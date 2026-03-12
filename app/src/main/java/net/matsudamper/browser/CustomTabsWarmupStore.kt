package net.matsudamper.browser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.browser.customtabs.CustomTabsSessionToken
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object CustomTabsWarmupStore {
    private const val MAX_SESSION_ENTRIES = 8
    private const val STALE_ENTRY_MS = 10 * 60 * 1000L

    private val lock = Any()
    private val entries = linkedMapOf<CustomTabsSessionToken, Entry>()

    @Volatile
    private var debugMayLaunchCount = 0

    @Volatile
    private var debugConsumeHitCount = 0

    @Volatile
    private var debugLastPreparedUrl: String? = null

    private data class Entry(
        var preparedSession: GeckoSession? = null,
        var preparedUrl: String? = null,
        var updatedAt: Long = System.currentTimeMillis(),
    )

    fun onWarmup(context: Context) {
        runOnMainThreadBlocking {
            GeckoRuntime.getDefault(context.applicationContext)
        }
    }

    fun onNewSession(token: CustomTabsSessionToken) {
        synchronized(lock) {
            cleanupLocked()
            ensureEntryLocked(token).updatedAt = System.currentTimeMillis()
        }
    }

    fun onMayLaunchUrl(
        context: Context,
        token: CustomTabsSessionToken,
        url: Uri?,
    ) {
        val targetUrl = url?.toString()?.takeIf { it.isNotBlank() } ?: return
        runOnMainThreadBlocking {
            val runtime = GeckoRuntime.getDefault(context.applicationContext)
            val session = synchronized(lock) {
                cleanupLocked()
                val entry = ensureEntryLocked(token)
                entry.updatedAt = System.currentTimeMillis()
                val existing = entry.preparedSession
                if (existing != null) {
                    entry.preparedUrl = targetUrl
                    existing
                } else {
                    GeckoSession().also { newSession ->
                        newSession.open(runtime)
                        entry.preparedSession = newSession
                        entry.preparedUrl = targetUrl
                    }
                }
            }
            session.loadUri(targetUrl)
        }
        debugMayLaunchCount++
        debugLastPreparedUrl = targetUrl
    }

    fun consumePreparedSession(
        token: CustomTabsSessionToken,
        launchUrl: String,
    ): GeckoSession? {
        val prepared = synchronized(lock) {
            cleanupLocked()
            val entry = entries[token] ?: return null
            entry.updatedAt = System.currentTimeMillis()
            val session = entry.preparedSession ?: return null
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
        debugConsumeHitCount++
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
        debugMayLaunchCount = 0
        debugConsumeHitCount = 0
        debugLastPreparedUrl = null
    }

    @VisibleForTesting
    fun getDebugMayLaunchCountForTesting(): Int = debugMayLaunchCount

    @VisibleForTesting
    fun getDebugConsumeHitCountForTesting(): Int = debugConsumeHitCount

    @VisibleForTesting
    fun getDebugLastPreparedUrlForTesting(): String? = debugLastPreparedUrl

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
