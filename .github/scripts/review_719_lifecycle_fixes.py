from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, found {count}: {old[:120]!r}")
    file.write_text(text.replace(old, new, 1))


def replace_between(path: str, start: str, end: str, replacement: str) -> None:
    file = Path(path)
    text = file.read_text()
    start_index = text.find(start)
    if start_index < 0:
        raise RuntimeError(f"{path}: start marker not found: {start!r}")
    if text.find(start, start_index + len(start)) >= 0:
        raise RuntimeError(f"{path}: start marker is not unique: {start!r}")
    end_index = text.find(end, start_index + len(start))
    if end_index < 0:
        raise RuntimeError(f"{path}: end marker not found: {end!r}")
    file.write_text(text[:start_index] + replacement + text[end_index:])


gecko_tab = "app/src/main/kotlin/net/matsudamper/browser/GeckoBrowserTab.kt"
replace_once(
    gecko_tab,
    "import net.matsudamper.browser.translate.TranslationPriorityLanguage\n",
    "import net.matsudamper.browser.translate.PageTranslationWebExtension\n"
    "import net.matsudamper.browser.translate.TranslationPriorityLanguage\n",
)
replace_once(
    gecko_tab,
    "    val findInPageWebExtension: FindInPageWebExtension = koinInject()\n",
    "    val findInPageWebExtension: FindInPageWebExtension = koinInject()\n"
    "    val pageTranslationWebExtension: PageTranslationWebExtension = koinInject()\n",
)
replace_once(
    gecko_tab,
    "    // FindInPageWebExtension のセッション登録\n",
    """    // ページ側へ接続トリガーを露出せず Native Messaging を開始できるよう、
    // 表示中セッションには content script より先に MessageDelegate を登録する。
    DisposableEffect(session, pageTranslationWebExtension) {
        pageTranslationWebExtension.registerSession(session)
        onDispose {
            pageTranslationWebExtension.unregisterSession(session)
        }
    }

    // FindInPageWebExtension のセッション登録
""",
)

screen = "app/src/main/kotlin/net/matsudamper/browser/BrowserTabScreenState.kt"
replace_once(
    screen,
    "    private var translationJob: Job? = null\n",
    "    private var translationJob: Job? = null\n"
    "    private var activeTranslationProvider: TranslationProvider? = null\n",
)
replace_between(
    screen,
    "        if (shouldResetTranslationOnLocationChange(",
    "        if (!url.startsWith(\"data:\")) {",
    """        if (
            shouldResetTranslationOnLocationChange(
                translationState,
                url,
                originalPageUrlForRevert,
                wasFullPageLoad,
            )
        ) {
            translationJob?.cancel()
            translationJob = null
            if (activeTranslationProvider == TranslationProvider.TRANSLATION_PROVIDER_LOCAL_AI) {
                pageTranslationWebExtension.stopTranslation(session, restoreOriginal = false)
            }
            activeTranslationProvider = null
            translationState = TranslationState.Idle
            originalPageUrlForRevert = null
            translationFromLanguage = null
            translationToLanguage = null
        }
""",
)
replace_once(
    screen,
    "        translationJob?.cancel()\n        translationJob = coroutineScope.launch {\n",
    """        translationJob?.cancel()
        if (activeTranslationProvider == TranslationProvider.TRANSLATION_PROVIDER_LOCAL_AI) {
            pageTranslationWebExtension.stopTranslation(session, restoreOriginal = true)
        }
        activeTranslationProvider = translationProvider
        translationJob = coroutineScope.launch {
""",
)
replace_between(
    screen,
    "    fun onRevertTranslation() {\n",
    "    fun onDismissTranslationError() {\n",
    """    fun onRevertTranslation() {
        closeTranslationBar(revertPage = true)
    }

""",
)
replace_between(
    screen,
    "    private fun closeTranslationBar(revertPage: Boolean) {\n",
    "    fun sharePage() {\n",
    """    private fun closeTranslationBar(revertPage: Boolean) {
        translationJob?.cancel()
        translationJob = null
        val savedUrl = originalPageUrlForRevert
        val provider = activeTranslationProvider
        activeTranslationProvider = null
        translationState = TranslationState.Idle
        originalPageUrlForRevert = null
        translationFromLanguage = null
        translationToLanguage = null
        if (provider == TranslationProvider.TRANSLATION_PROVIDER_LOCAL_AI) {
            pageTranslationWebExtension.stopTranslation(session, restoreOriginal = revertPage)
        } else if (revertPage && savedUrl != null) {
            clearPageLoadError()
            session.loadUri(savedUrl)
        }
    }

""",
)

extension = "app/src/main/kotlin/net/matsudamper/browser/translate/PageTranslationWebExtension.kt"
replace_once(
    extension,
    "    private var extension: WebExtension? = null\n\n    @Volatile\n    private var installationError: Throwable? = null",
    "    @Volatile\n    private var extension: WebExtension? = null\n\n"
    "    @Volatile\n    private var installationError: Throwable? = null",
)
replace_once(
    extension,
    """    private val attachedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val pendingScans = ConcurrentHashMap<GeckoSession, PendingScan>()""",
    """    private val attachedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val sessionsWaitingForExtension: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val pendingScans = ConcurrentHashMap<GeckoSession, PendingScan>()""",
)
replace_between(
    extension,
    "                    extension = installedExtension\n",
    "                },\n                { error ->\n",
    """                    extension = installedExtension
                    installationError = null
                    sessionsWaitingForExtension.toList().forEach { session ->
                        if (sessionsWaitingForExtension.remove(session)) {
                            attachSessionDelegate(session, installedExtension)
                        }
                    }
                    pendingScans.keys.forEach { session ->
                        attachSessionDelegate(session, installedExtension)
                    }
""",
)
replace_once(
    extension,
    "                    pendingScans.clear()\n",
    "                    pendingScans.clear()\n                    sessionsWaitingForExtension.clear()\n",
)
replace_once(
    extension,
    "    suspend fun scanPage(session: GeckoSession): PageSnapshot {\n",
    """    fun registerSession(session: GeckoSession) {
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

    suspend fun scanPage(session: GeckoSession): PageSnapshot {
""",
)
replace_between(
    extension,
    "        val installedExtension = extension\n",
    "        sendStartIfReady(session)\n",
    "        registerSession(session)\n",
)
replace_once(
    extension,
    "                                attachedSessions.remove(session)\n",
    "",
)
replace_between(
    extension,
    "    private fun requestImmediateConnection(session: GeckoSession) {\n",
    "    private fun sendMessage(session: GeckoSession, message: JSONObject): Boolean {\n",
    "",
)
replace_between(
    extension,
    "        private const val CONNECT_SCRIPT_URI =\n",
    "        private const val SCAN_TIMEOUT_MS = 10_000L\n",
    "",
)

content_js = "app/src/main/assets/web_extensions/page_translation_bridge/content.js"
replace_once(content_js, "  const IMMEDIATE_CONNECT_MESSAGE = '__browsem_page_translation_connect__';\n", "")
replace_once(
    content_js,
    """    // 翻訳を使わないページではネイティブ側が MessageDelegate を張らないため handshake は必ず失敗する。
    // 無制限に再試行すると全タブ・全ページでタイマーが回り続けるので、数回で諦める。
    // 翻訳開始時はネイティブ側が接続要求メッセージを注入するため、そこからの connect() で復帰できる。""",
    """    // ネイティブ側は表示中セッションへ先に MessageDelegate を登録する。
    // Compose と content script の開始順序の差を吸収するため、有限回だけ再試行する。""",
)
replace_between(
    content_js,
    "  window.addEventListener('message', function (event) {\n",
    "  waitForNative();\n})();\n",
    """  function retryNativeHandshakeWhenVisible() {
    if (document.visibilityState !== 'visible' || port !== null) return;
    handshakeRetryCount = 0;
    waitForNative();
  }

  document.addEventListener('visibilitychange', retryNativeHandshakeWhenVisible);
  window.addEventListener('pageshow', retryNativeHandshakeWhenVisible);

""",
)
