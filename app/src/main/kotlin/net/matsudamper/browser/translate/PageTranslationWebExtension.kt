package net.matsudamper.browser.translate

import android.os.SystemClock
import android.util.Log
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import net.matsudamper.browser.data.crashlog.CrashLogRepository
import org.json.JSONArray
import org.json.JSONObject
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

    /**
     * 翻訳結果のDOM反映結果。
     *
     * [requeuedCount] はページ側が書き換えたため反映せず、動的セグメントとして
     * 翻訳し直す件数を示す。
     */
    data class ApplyResult(
        val documentMatched: Boolean,
        val appliedCount: Int,
        val requeuedCount: Int,
    )

    data class TranslationResult(
        val id: String,
        val sourceText: String,
        val translatedText: String,
    )

    private data class PendingScan(
        val requestId: String,
        val expectedUrl: String?,
        val deferred: CompletableDeferred<PageSnapshot>,
        val startedAtElapsedRealtime: Long,
        val segments: MutableList<Segment> = mutableListOf(),
        var documentId: String? = null,
        var htmlLanguage: String? = null,
    )

    private data class PendingApply(
        val session: GeckoSession,
        val documentId: String,
        val deferred: CompletableDeferred<ApplyResult>,
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

    @Volatile
    private var extension: WebExtension? = null

    @Volatile
    private var installationError: Throwable? = null
    private val requestSequence = AtomicLong(0)
    private val sessionPorts = ConcurrentHashMap<GeckoSession, WebExtension.Port>()
    private val attachedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val sessionsWaitingForExtension: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val pendingScans = ConcurrentHashMap<GeckoSession, PendingScan>()
    private val pendingApplies = ConcurrentHashMap<String, PendingApply>()
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
                    sessionsWaitingForExtension.toList().forEach { session ->
                        if (sessionsWaitingForExtension.remove(session)) {
                            attachSessionDelegate(session, installedExtension)
                        }
                    }
                    pendingScans.keys.forEach { session ->
                        attachSessionDelegate(session, installedExtension)
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
                    pendingApplies.values.forEach { pending ->
                        pending.deferred.completeExceptionally(cause)
                    }
                    pendingApplies.clear()
                    sessionsWaitingForExtension.clear()
                },
            )
    }

    fun registerSession(session: GeckoSession) {
        if (installationError != null) return
        val installedExtension = extension
        if (installedExtension != null) {
            attachSessionDelegate(session, installedExtension)
            return
        }

        sessionsWaitingForExtension.add(session)
        extension?.let { installedAfterRegistration ->
            if (sessionsWaitingForExtension.remove(session)) {
                attachSessionDelegate(session, installedAfterRegistration)
            }
        }
    }

    fun unregisterSession(session: GeckoSession) {
        sessionsWaitingForExtension.remove(session)
        stopTranslation(session, restoreOriginal = false)
        sessionPorts.remove(session)
        attachedSessions.remove(session)
        extension?.let { installedExtension ->
            session.webExtensionController.setMessageDelegate(installedExtension, null, NATIVE_APP_ID)
        }
    }

    /**
     * 表示中のページの翻訳対象テキストを取得する。
     *
     * [expectedUrl] を渡すと、bfcache へ退避したドキュメントなど別ページからの応答を弾く。
     */
    suspend fun scanPage(session: GeckoSession, expectedUrl: String?): PageSnapshot {
        installationError?.let { throw it }
        stopActiveTranslation(session)
        awaitingActivationDocuments.remove(session)
        bufferedDynamicSegments.remove(session)

        val pending = PendingScan(
            requestId = "scan-${requestSequence.incrementAndGet()}",
            expectedUrl = expectedUrl,
            deferred = CompletableDeferred(),
            startedAtElapsedRealtime = SystemClock.elapsedRealtime(),
        )
        pendingScans.put(session, pending)?.deferred?.cancel()
        registerSession(session)
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
                "ページ翻訳DOMの取得が${SCAN_TIMEOUT_MS}ms以内に完了しませんでした" +
                    "(ブリッジ接続=${sessionPorts[session] != null}, 受信セグメント=${pending.segments.size})",
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
        val sent = sendApplyMessage(
            session = session,
            documentId = documentId,
            translations = translations,
            requestId = null,
        )
        if (!sent) {
            saveInfo(
                title = "ページ翻訳結果の送信失敗",
                body = "documentId=$documentId\ntranslationCount=${translations.size}",
            )
        }
    }

    suspend fun applyTranslationsAndAwait(
        session: GeckoSession,
        documentId: String,
        translations: List<TranslationResult>,
    ): ApplyResult {
        if (translations.isEmpty()) return ApplyResult(documentMatched = true, appliedCount = 0, requeuedCount = 0)
        val requestId = "apply-${requestSequence.incrementAndGet()}"
        val pending = PendingApply(
            session = session,
            documentId = documentId,
            deferred = CompletableDeferred(),
        )
        pendingApplies[requestId] = pending
        val sent = sendApplyMessage(
            session = session,
            documentId = documentId,
            translations = translations,
            requestId = requestId,
        )
        if (!sent) {
            pendingApplies.remove(requestId, pending)
            throw IllegalStateException("ページ翻訳結果をDOMへ送信できませんでした")
        }
        return try {
            withTimeout(APPLY_TIMEOUT_MS) {
                pending.deferred.await()
            }
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException(
                "ページ翻訳DOM反映の確認が${APPLY_TIMEOUT_MS}ms以内に完了しませんでした",
                error,
            )
        } finally {
            pendingApplies.remove(requestId, pending)
        }
    }

    fun stopTranslation(session: GeckoSession, restoreOriginal: Boolean) {
        stopActiveTranslation(session)
        cancelPendingApplies(session)
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
                                val cause = IllegalStateException("ページ翻訳ブリッジとの接続が切断されました")
                                val pending = pendingScans.remove(session)
                                if (pending != null) {
                                    pending.deferred.completeExceptionally(cause)
                                    val elapsedMs = SystemClock.elapsedRealtime() - pending.startedAtElapsedRealtime
                                    saveInfo(
                                        title = "ページ翻訳ブリッジ接続切断",
                                        body = buildScanDiagnostics(session, pending, elapsedMs),
                                    )
                                }
                                failPendingApplies(session, cause)
                                // 継続翻訳は content script の再接続で再開できるため、
                                // 切断だけでは終了させない
                                if (activeTranslations.containsKey(session)) {
                                    saveInfo(
                                        title = "ページ翻訳ブリッジ再接続待ち",
                                        body = "documentId=${activeTranslations[session]?.documentId.orEmpty()}",
                                    )
                                }
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
            "scanFailed" -> handleScanFailed(session, json)
            "applyResult" -> handleApplyResult(session, json)
            "dynamicSegments" -> handleDynamicSegments(session, json)
        }
    }

    private fun handleScanStart(session: GeckoSession, json: JSONObject) {
        val pending = pendingScans[session] ?: return
        if (pending.requestId != json.optString("requestId")) return
        val documentUrl = json.optString("documentUrl").takeIf { it.isNotBlank() }
        if (!isExpectedDocument(pending.expectedUrl, documentUrl)) {
            pendingScans.remove(session, pending)
            pending.deferred.completeExceptionally(
                IllegalStateException("表示中のページとは別のドキュメントから応答されました"),
            )
            saveInfo(
                title = "ページ翻訳スキャン対象不一致",
                body = "expected=${toDiagnosticUrl(pending.expectedUrl)}\ndocument=${toDiagnosticUrl(documentUrl)}",
            )
            return
        }
        pending.documentId = json.optString("documentId").takeIf { it.isNotBlank() }
        pending.htmlLanguage = json.optString("htmlLanguage").takeIf { it.isNotBlank() }
    }

    /** 診断ログに閲覧内容が残らないよう、オリジンと不可逆ハッシュだけにする */
    private fun toDiagnosticUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val origin = runCatching {
            val parsed = URI(url)
            val port = if (parsed.port >= 0) ":${parsed.port}" else ""
            "${parsed.scheme.orEmpty()}://${parsed.host.orEmpty()}$port"
        }.getOrDefault("")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.substringBefore('#').toByteArray())
            .take(DIAGNOSTIC_HASH_BYTE_COUNT)
            .joinToString("") { byte -> "%02x".format(byte) }
        return "$origin#$hash"
    }

    /** ページ内リンク（#）やクエリ差分は同じドキュメントとして扱う */
    private fun isExpectedDocument(expectedUrl: String?, documentUrl: String?): Boolean {
        if (expectedUrl.isNullOrBlank() || documentUrl.isNullOrBlank()) return true
        return expectedUrl.substringBefore('#') == documentUrl.substringBefore('#')
    }

    private fun handleScanFailed(session: GeckoSession, json: JSONObject) {
        val pending = pendingScans[session] ?: return
        if (pending.requestId != json.optString("requestId")) return
        pendingScans.remove(session, pending)
        val reason = json.optString("reason").takeIf { it.isNotBlank() }.orEmpty()
        pending.deferred.completeExceptionally(
            IllegalStateException("ページ翻訳DOMの取得に失敗しました: $reason"),
        )
        saveInfo(
            title = "ページ翻訳DOMスキャン失敗",
            body = buildScanDiagnostics(
                session = session,
                pending = pending,
                elapsedMs = SystemClock.elapsedRealtime() - pending.startedAtElapsedRealtime,
            ) + "\nreason=$reason",
        )
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

    private fun handleApplyResult(session: GeckoSession, json: JSONObject) {
        val requestId = json.optString("requestId")
        if (requestId.isBlank()) return
        val pending = pendingApplies[requestId] ?: return
        if (pending.session !== session) return
        pending.deferred.complete(
            ApplyResult(
                documentMatched = json.optBoolean("documentMatched", false),
                appliedCount = json.optInt("appliedCount", 0).coerceAtLeast(0),
                requeuedCount = json.optInt("requeuedCount", 0).coerceAtLeast(0),
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

    private fun sendApplyMessage(
        session: GeckoSession,
        documentId: String,
        translations: List<TranslationResult>,
        requestId: String?,
    ): Boolean {
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
        return sendMessage(
            session,
            JSONObject().apply {
                put("action", "apply")
                put("documentId", documentId)
                put("translations", payload)
                if (requestId != null) {
                    put("requestId", requestId)
                }
            },
        )
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

    private fun cancelPendingApplies(session: GeckoSession) {
        pendingApplies.forEach { (requestId, pending) ->
            if (pending.session === session && pendingApplies.remove(requestId, pending)) {
                pending.deferred.cancel()
            }
        }
    }

    private fun failPendingApplies(session: GeckoSession, cause: Throwable) {
        pendingApplies.forEach { (requestId, pending) ->
            if (pending.session === session && pendingApplies.remove(requestId, pending)) {
                pending.deferred.completeExceptionally(cause)
            }
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
        private const val DIAGNOSTIC_HASH_BYTE_COUNT = 8
        private const val SCAN_TIMEOUT_MS = 20_000L
        private const val APPLY_TIMEOUT_MS = 5_000L
        private const val SLOW_SCAN_THRESHOLD_MS = 2_000L
        private const val MAX_BUFFERED_DYNAMIC_SEGMENTS = 512
    }
}
