package net.matsudamper.browser.translate

import android.os.SystemClock
import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import net.matsudamper.browser.data.crashlog.CrashLogRepository
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

class PageTranslationWebExtension(
    private val crashLogRepository: CrashLogRepository,
) {
    data class Segment(
        val id: String,
        val text: String,
    )

    data class PageSnapshot(
        val documentId: String,
        val htmlLanguage: String?,
        val segments: List<Segment>,
    )

    data class TranslationResult(
        val id: String,
        val sourceText: String,
        val translatedText: String,
    )

    private data class PendingScan(
        val requestId: String,
        val deferred: CompletableDeferred<PageSnapshot>,
        val startedAtElapsedRealtime: Long,
        val segments: MutableList<Segment> = mutableListOf(),
        var documentId: String? = null,
        var htmlLanguage: String? = null,
    )

    private data class ActiveTranslation(
        val documentId: String,
        val onSegments: (List<Segment>) -> Unit,
        val onStopped: () -> Unit,
    )

    private data class BufferedSegments(
        val documentId: String,
        val segments: List<Segment>,
    )

    private var extension: WebExtension? = null

    @Volatile
    private var installationError: Throwable? = null
    private val requestSequence = AtomicLong(0)
    private val sessionPorts = ConcurrentHashMap<GeckoSession, WebExtension.Port>()
    private val attachedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val pendingScans = ConcurrentHashMap<GeckoSession, PendingScan>()
    private val awaitingActivationDocuments = ConcurrentHashMap<GeckoSession, String>()
    private val activeTranslations = ConcurrentHashMap<GeckoSession, ActiveTranslation>()
    private val bufferedDynamicSegments = ConcurrentHashMap<GeckoSession, BufferedSegments>()

    fun install(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
            .accept(
                { installedExtension ->
                    if (installedExtension == null) return@accept
                    extension = installedExtension
                    installationError = null
                    pendingScans.keys.forEach { session ->
                        attachSessionDelegate(session, installedExtension)
                        requestImmediateConnection(session)
                    }
                },
                { error ->
                    val cause = error ?: IllegalStateException("ページ翻訳ブリッジのインストールに失敗しました")
                    installationError = cause
                    Log.e(TAG, "ページ翻訳ブリッジのインストールに失敗", cause)
                    saveInfo(
                        title = "ページ翻訳ブリッジのインストール失敗",
                        body = "error=${cause.javaClass.name}\nmessage=${cause.message.orEmpty()}",
                    )
                    pendingScans.values.forEach { pending ->
                        pending.deferred.completeExceptionally(cause)
                    }
                    pendingScans.clear()
                },
            )
    }

    suspend fun scanPage(session: GeckoSession): PageSnapshot {
        installationError?.let { throw it }
        stopActiveTranslation(session)
        awaitingActivationDocuments.remove(session)
        bufferedDynamicSegments.remove(session)

        val pending = PendingScan(
            requestId = "scan-${requestSequence.incrementAndGet()}",
            deferred = CompletableDeferred(),
            startedAtElapsedRealtime = SystemClock.elapsedRealtime(),
        )
        pendingScans.put(session, pending)?.deferred?.cancel()
        val installedExtension = extension
        if (installedExtension != null) {
            attachSessionDelegate(session, installedExtension)
            requestImmediateConnection(session)
        }
        sendStartIfReady(session)

        return try {
            val snapshot = withTimeout(SCAN_TIMEOUT_MS) {
                pending.deferred.await()
            }
            val elapsedMs = SystemClock.elapsedRealtime() - pending.startedAtElapsedRealtime
            if (elapsedMs >= SLOW_SCAN_THRESHOLD_MS) {
                saveInfo(
                    title = "ページ翻訳DOMスキャン遅延",
                    body = buildScanDiagnostics(session, pending, elapsedMs),
                )
            }
            snapshot
        } catch (error: TimeoutCancellationException) {
            val elapsedMs = SystemClock.elapsedRealtime() - pending.startedAtElapsedRealtime
            saveInfo(
                title = "ページ翻訳DOMスキャンタイムアウト",
                body = buildScanDiagnostics(session, pending, elapsedMs),
            )
            throw IllegalStateException(
                "ページ翻訳DOMの取得が${SCAN_TIMEOUT_MS}ms以内に完了しませんでした",
                error,
            )
        } finally {
            pendingScans.remove(session, pending)
        }
    }

    fun activateTranslation(
        session: GeckoSession,
        documentId: String,
        onSegments: (List<Segment>) -> Unit,
        onStopped: () -> Unit,
    ): Boolean {
        if (sessionPorts[session] == null || awaitingActivationDocuments[session] != documentId) {
            onStopped()
            return false
        }
        activeTranslations.put(
            session,
            ActiveTranslation(
                documentId = documentId,
                onSegments = onSegments,
                onStopped = onStopped,
            ),
        )?.onStopped?.invoke()
        awaitingActivationDocuments.remove(session, documentId)

        val buffered = bufferedDynamicSegments.remove(session)
        if (buffered?.documentId == documentId && buffered.segments.isNotEmpty()) {
            onSegments(buffered.segments)
        }
        return true
    }

    fun applyTranslations(
        session: GeckoSession,
        documentId: String,
        translations: List<TranslationResult>,
    ) {
        if (translations.isEmpty()) return
        val payload = JSONArray()
        translations.forEach { translation ->
            payload.put(
                JSONObject().apply {
                    put("id", translation.id)
                    put("sourceText", translation.sourceText)
                    put("translatedText", translation.translatedText)
                },
            )
        }
        sendMessage(
            session,
            JSONObject().apply {
                put("action", "apply")
                put("documentId", documentId)
                put("translations", payload)
            },
        )
    }

    fun stopTranslation(session: GeckoSession, restoreOriginal: Boolean) {
        stopActiveTranslation(session)
        awaitingActivationDocuments.remove(session)
        bufferedDynamicSegments.remove(session)
        pendingScans.remove(session)?.deferred?.cancel()
        sendMessage(
            session,
            JSONObject().apply {
                put("action", if (restoreOriginal) "revert" else "stop")
            },
        )
    }

    private fun attachSessionDelegate(session: GeckoSession, installedExtension: WebExtension) {
        if (!attachedSessions.add(session)) return
        session.webExtensionController.setMessageDelegate(
            installedExtension,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender,
                ): GeckoResult<Any>? {
                    val json = message as? JSONObject ?: return null
                    if (json.optString("action") != "ready") return null
                    return GeckoResult.fromValue<Any>(
                        JSONObject().apply {
                            put("connect", true)
                        },
                    )
                }

                override fun onConnect(port: WebExtension.Port) {
                    sessionPorts[session] = port
                    port.setDelegate(
                        object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                if (sessionPorts[session] !== port) return
                                val json = message as? JSONObject ?: return
                                handlePortMessage(session, json)
                            }

                            override fun onDisconnect(port: WebExtension.Port) {
                                if (!sessionPorts.remove(session, port)) return
                                attachedSessions.remove(session)
                                val pending = pendingScans.remove(session)
                                if (pending != null) {
                                    pending.deferred.completeExceptionally(
                                        IllegalStateException("ページ翻訳ブリッジとの接続が切断されました"),
                                    )
                                    val elapsedMs = SystemClock.elapsedRealtime() - pending.startedAtElapsedRealtime
                                    saveInfo(
                                        title = "ページ翻訳ブリッジ接続切断",
                                        body = buildScanDiagnostics(session, pending, elapsedMs),
                                    )
                                }
                                awaitingActivationDocuments.remove(session)
                                bufferedDynamicSegments.remove(session)
                                stopActiveTranslation(session)
                            }
                        },
                    )
                    sendStartIfReady(session)
                }
            },
            NATIVE_APP_ID,
        )
    }

    private fun handlePortMessage(session: GeckoSession, json: JSONObject) {
        when (json.optString("action")) {
            "scanStart" -> handleScanStart(session, json)
            "scanSegments" -> handleScanSegments(session, json)
            "scanComplete" -> handleScanComplete(session, json)
            "dynamicSegments" -> handleDynamicSegments(session, json)
        }
    }

    private fun handleScanStart(session: GeckoSession, json: JSONObject) {
        val pending = pendingScans[session] ?: return
        if (pending.requestId != json.optString("requestId")) return
        pending.documentId = json.optString("documentId").takeIf { it.isNotBlank() }
        pending.htmlLanguage = json.optString("htmlLanguage").takeIf { it.isNotBlank() }
    }

    private fun handleScanSegments(session: GeckoSession, json: JSONObject) {
        val pending = pendingScans[session] ?: return
        if (pending.requestId != json.optString("requestId")) return
        pending.segments.addAll(parseSegments(json.optJSONArray("segments")))
    }

    private fun handleScanComplete(session: GeckoSession, json: JSONObject) {
        val pending = pendingScans[session] ?: return
        if (pending.requestId != json.optString("requestId")) return
        val documentId = pending.documentId ?: return
        awaitingActivationDocuments[session] = documentId
        pending.deferred.complete(
            PageSnapshot(
                documentId = documentId,
                htmlLanguage = pending.htmlLanguage,
                segments = pending.segments.toList(),
            ),
        )
    }

    private fun handleDynamicSegments(session: GeckoSession, json: JSONObject) {
        val documentId = json.optString("documentId")
        if (documentId.isBlank()) return
        val segments = parseSegments(json.optJSONArray("segments"))
        if (segments.isEmpty()) return

        val active = activeTranslations[session]
        if (active?.documentId == documentId) {
            active.onSegments(segments)
            return
        }
        if (awaitingActivationDocuments[session] != documentId) return
        bufferedDynamicSegments.compute(session) { _, current ->
            val currentSegments = if (current?.documentId == documentId) current.segments else listOf()
            BufferedSegments(
                documentId = documentId,
                segments = (currentSegments + segments).takeLast(MAX_BUFFERED_DYNAMIC_SEGMENTS),
            )
        }
    }

    private fun parseSegments(array: JSONArray?): List<Segment> {
        if (array == null) return listOf()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val text = item.optString("text")
                if (id.isBlank() || text.isBlank()) continue
                add(Segment(id = id, text = text))
            }
        }
    }

    private fun sendStartIfReady(session: GeckoSession) {
        val pending = pendingScans[session] ?: return
        if (sessionPorts[session] == null) return
        sendMessage(
            session,
            JSONObject().apply {
                put("action", "start")
                put("requestId", pending.requestId)
            },
        )
    }

    private fun requestImmediateConnection(session: GeckoSession) {
        if (sessionPorts[session] != null) return
        try {
            session.loadUri(CONNECT_SCRIPT_URI)
        } catch (error: RuntimeException) {
            Log.w(TAG, "ページ翻訳ブリッジの即時接続要求に失敗", error)
            saveInfo(
                title = "ページ翻訳ブリッジ即時接続要求失敗",
                body = "error=${error.javaClass.name}\nmessage=${error.message.orEmpty()}",
            )
        }
    }

    private fun sendMessage(session: GeckoSession, message: JSONObject): Boolean {
        val port = sessionPorts[session] ?: return false
        return try {
            port.postMessage(message)
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "ページ翻訳ブリッジへの送信に失敗: action=${message.optString("action")}", error)
            saveInfo(
                title = "ページ翻訳ブリッジ送信失敗",
                body = "action=${message.optString("action")}\nerror=${error.javaClass.name}\nmessage=${error.message.orEmpty()}",
            )
            false
        }
    }

    private fun buildScanDiagnostics(
        session: GeckoSession,
        pending: PendingScan,
        elapsedMs: Long,
    ): String = buildString {
        appendLine("requestId=${pending.requestId}")
        appendLine("elapsedMs=$elapsedMs")
        appendLine("extensionInstalled=${extension != null}")
        appendLine("delegateAttached=${attachedSessions.contains(session)}")
        appendLine("portConnected=${sessionPorts[session] != null}")
        appendLine("documentStarted=${pending.documentId != null}")
        append("segmentCount=${pending.segments.size}")
    }

    private fun saveInfo(title: String, body: String) {
        try {
            crashLogRepository.saveInfoSync(title, body)
        } catch (error: RuntimeException) {
            Log.w(TAG, "翻訳診断ログの保存に失敗", error)
        }
    }

    private fun stopActiveTranslation(session: GeckoSession) {
        activeTranslations.remove(session)?.onStopped?.invoke()
    }

    companion object {
        private const val TAG = "PageTranslationExt"
        private const val NATIVE_APP_ID = "pageTranslationBridge"
        private const val EXTENSION_ID = "page-translation-bridge@browsem"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/page_translation_bridge/"
        private const val CONNECT_SCRIPT_URI =
            "javascript:void(window.postMessage('__browsem_page_translation_connect__','*'))"
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val SLOW_SCAN_THRESHOLD_MS = 2_000L
        private const val MAX_BUFFERED_DYNAMIC_SEGMENTS = 512
    }
}
