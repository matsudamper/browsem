package net.matsudamper.browser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult

@Stable
internal class WebShareFilesState(
    private val coroutineScope: CoroutineScope,
) {
    data class Pending(
        val requestId: String,
        val geckoResult: GeckoResult<Any>,
        val cacheDir: File,
        var completed: Boolean = false,
    )

    var pending by mutableStateOf<Pending?>(null)
    private val retainedCacheDirs = mutableStateListOf<File>()

    fun finish(success: Boolean, error: String? = null) {
        val active = pending ?: return
        if (active.completed) return
        active.completed = true
        pending = null
        val response = if (success) {
            JSONObject().put("success", true)
        } else {
            JSONObject()
                .put("success", false)
                .put("error", error ?: "共有がキャンセルされました")
                .put("errorName", "AbortError")
        }
        active.geckoResult.complete(response)
        if (success) {
            scheduleCacheCleanup(active.cacheDir)
        } else {
            active.cacheDir.deleteRecursively()
        }
    }

    fun cleanupOnDispose() {
        pending?.let { active ->
            if (!active.completed) {
                active.geckoResult.complete(
                    JSONObject()
                        .put("success", false)
                        .put("error", "共有がキャンセルされました")
                        .put("errorName", "AbortError"),
                )
            }
            active.cacheDir.deleteRecursively()
        }
        pending = null
        retainedCacheDirs.forEach { it.deleteRecursively() }
        retainedCacheDirs.clear()
    }

    private fun scheduleCacheCleanup(cacheDir: File) {
        retainedCacheDirs.add(cacheDir)
        coroutineScope.launch {
            delay(WEB_SHARE_FILES_CACHE_RETENTION_MS)
            cacheDir.deleteRecursively()
            retainedCacheDirs.remove(cacheDir)
        }
    }
}
