package net.matsudamper.browser

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.crashlog.CrashLogRepository
import net.matsudamper.browser.translate.PageTranslationCache
import net.matsudamper.browser.translate.PageTranslationWebExtension
import net.matsudamper.browser.translate.TranslationLanguages
import net.matsudamper.browser.translate.TranslationPriorityLanguage
import net.matsudamper.browser.translate.TranslationProgress
import net.matsudamper.browser.translate.Translator
import org.mozilla.geckoview.GeckoSession

private const val TAG = "BrowserTabTranslationState"

/**
 * タブ 1 つあたりのページ翻訳状態。
 *
 * 翻訳バーの表示内容と、翻訳ジョブ・ページ復元の後始末を担当する。
 */
@Stable
internal class BrowserTabTranslationState(
    private val coroutineScope: CoroutineScope,
    private val pageTranslationWebExtension: PageTranslationWebExtension,
    private val crashLogRepository: CrashLogRepository,
    private val session: () -> GeckoSession,
    private val currentPageUrl: () -> String,
    private val loadOriginalPage: (String) -> Unit,
) {
    var state by mutableStateOf(TranslationState.Idle)
        private set

    /** 翻訳失敗時の理由。どの段階で失敗したかを翻訳バーへ表示する */
    var errorMessage: String? by mutableStateOf(null)
        private set

    /** ページ内テキストの翻訳進捗。初期反映後も継続翻訳が進むため、完了まで表示する */
    var progress: TranslationProgress? by mutableStateOf(null)
        private set

    var originalPageUrlForRevert by mutableStateOf<String?>(null)
        private set

    var detectedPageLanguage by mutableStateOf<String?>(null)
        private set

    var fromLanguage by mutableStateOf<String?>(null)
        private set

    var toLanguage by mutableStateOf<String?>(null)
        private set

    private var translationJob: Job? = null
    private var activeProvider: TranslationProvider? = null

    /** 原文表示と再翻訳を往復しても訳し直さないよう、ページ遷移まで訳文を持ち続ける */
    private val pageTranslationCache = PageTranslationCache()

    fun onTranslate(translationProvider: TranslationProvider, geminiNanoModelKey: String) {
        when (state) {
            TranslationState.Idle -> {
                runTranslation(
                    translationProvider,
                    geminiNanoModelKey = geminiNanoModelKey,
                    fromLanguage = detectedPageLanguage,
                    toLanguage = TranslationPriorityLanguage.TO,
                )
            }

            TranslationState.Loading,
            TranslationState.ScanningPage,
            TranslationState.DetectingLanguage,
            TranslationState.PreparingModel,
            TranslationState.Translating,
            TranslationState.Translated,
            -> {
                close(revertPage = true)
            }

            TranslationState.Error -> {
                close(revertPage = false)
            }
        }
    }

    /** ステータスバーの言語ドロップダウンから再翻訳を実行する */
    fun onRetranslate(
        translationProvider: TranslationProvider,
        geminiNanoModelKey: String,
        fromLanguage: String?,
        toLanguage: String,
    ) {
        if (state.isInProgress) return
        runTranslation(
            translationProvider,
            geminiNanoModelKey = geminiNanoModelKey,
            fromLanguage = fromLanguage,
            toLanguage = toLanguage,
        )
    }

    /**
     * 画面から離れて翻訳バーを失うときに呼ぶ。
     *
     * 継続翻訳は推論モデルを開いたままにするため、訳文は残しつつ止める。
     */
    fun onScreenDisposed() {
        cancelJob()
        stopBridgeIfActive(restoreOriginal = false)
        activeProvider = null
    }

    fun onRevert() {
        close(revertPage = true)
    }

    fun onDismissError() {
        close(revertPage = false)
    }

    /** GeckoView が検出したページ言語を取り込む */
    fun onDetectedLanguageChanged(languageTag: String) {
        detectedPageLanguage = languageTag
    }

    /**
     * ページ遷移に追従して翻訳状態と訳文キャッシュを破棄する。
     *
     * data: URL（翻訳結果ページ）では検出言語も保持する。
     */
    fun onLocationChange(url: String, isFullPageLoad: Boolean) {
        if (shouldResetTranslationOnLocationChange(state, url, originalPageUrlForRevert, isFullPageLoad)) {
            cancelJob()
            stopBridgeIfActive(restoreOriginal = false)
            activeProvider = null
            clearBarState()
        }
        if (shouldClearTranslationCacheOnLocationChange(url, isFullPageLoad)) {
            pageTranslationCache.clear()
        }
        if (!url.startsWith("data:")) {
            detectedPageLanguage = null
        }
    }

    private fun runTranslation(
        translationProvider: TranslationProvider,
        geminiNanoModelKey: String,
        fromLanguage: String?,
        toLanguage: String,
    ) {
        translationJob?.cancel()
        stopBridgeIfActive(restoreOriginal = true)
        activeProvider = translationProvider
        translationJob = coroutineScope.launch {
            if (originalPageUrlForRevert == null) {
                originalPageUrlForRevert = currentPageUrl()
            }
            // 非同期処理完了後にページ遷移済みかを検出するために翻訳開始時のURLを保持する
            val translationStartUrl = originalPageUrlForRevert
            state = TranslationState.Loading
            errorMessage = null
            progress = null
            val pageUrl = translationStartUrl ?: currentPageUrl()
            val result = runCatching {
                PageTranslator(
                    session = session(),
                    currentPageUrl = pageUrl,
                    pageTranslationWebExtension = pageTranslationWebExtension,
                    pageTranslationCache = pageTranslationCache,
                    crashLogRepository = crashLogRepository,
                ).translatePage(
                    provider = translationProvider,
                    fromLanguage = fromLanguage,
                    toLanguage = toLanguage,
                    geminiNanoModelKey = geminiNanoModelKey,
                    onTranslateStateChanged = { translateState ->
                        if (originalPageUrlForRevert == translationStartUrl) {
                            state = translateState.toTranslationState()
                        }
                    },
                    onTranslateProgressChanged = { translationProgress ->
                        if (originalPageUrlForRevert == translationStartUrl) {
                            progress = translationProgress
                        }
                    },
                )
            }
            // CancellationException は runCatching で握りつぶさずに伝播させる。
            // キャンセル済みジョブが新ジョブの状態を上書きするのを防ぐ。
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            // 翻訳中にページ遷移が発生した場合（onLocationChange が originalPageUrlForRevert をクリア済み）は
            // 翻訳結果を破棄して翻訳バーを表示しない
            if (originalPageUrlForRevert != translationStartUrl) return@launch
            applyTranslationResult(result)
        }
    }

    private fun applyTranslationResult(result: Result<TranslationLanguages?>) {
        if (result.isSuccess) {
            val langs = result.getOrNull()
            fromLanguage = langs?.fromLanguage
            toLanguage = langs?.toLanguage
            errorMessage = null
            state = TranslationState.Translated
        } else {
            val error = result.exceptionOrNull()
            Log.e(TAG, "翻訳に失敗しました", error)
            fromLanguage = null
            toLanguage = null
            errorMessage = error?.message?.takeIf { it.isNotBlank() }
            state = TranslationState.Error
        }
    }

    private fun close(revertPage: Boolean) {
        cancelJob()
        val savedUrl = originalPageUrlForRevert
        val usedBridge = usesPageTranslationBridge(activeProvider)
        activeProvider = null
        clearBarState()
        if (usedBridge) {
            pageTranslationWebExtension.stopTranslation(session(), restoreOriginal = revertPage)
        } else if (revertPage && savedUrl != null) {
            loadOriginalPage(savedUrl)
        }
    }

    private fun cancelJob() {
        translationJob?.cancel()
        translationJob = null
    }

    private fun stopBridgeIfActive(restoreOriginal: Boolean) {
        if (usesPageTranslationBridge(activeProvider)) {
            pageTranslationWebExtension.stopTranslation(session(), restoreOriginal = restoreOriginal)
        }
    }

    private fun clearBarState() {
        state = TranslationState.Idle
        originalPageUrlForRevert = null
        fromLanguage = null
        toLanguage = null
        errorMessage = null
        progress = null
    }
}

/** ページ内 DOM を書き換えて翻訳するプロバイダーかどうかを判定する */
internal fun usesPageTranslationBridge(provider: TranslationProvider?): Boolean = when (provider) {
    TranslationProvider.TRANSLATION_PROVIDER_LOCAL_AI,
    TranslationProvider.TRANSLATION_PROVIDER_GEMINI_NANO,
    -> true

    TranslationProvider.TRANSLATION_PROVIDER_GECKO,
    TranslationProvider.UNRECOGNIZED,
    null,
    -> false
}

private fun Translator.TranslateState.toTranslationState(): TranslationState = when (this) {
    Translator.TranslateState.PAGE_SCAN -> TranslationState.ScanningPage
    Translator.TranslateState.LANGUAGE_DETECTION -> TranslationState.DetectingLanguage
    Translator.TranslateState.MODEL_DOWNLOAD -> TranslationState.PreparingModel
    Translator.TranslateState.TRANSLATING -> TranslationState.Translating
}
