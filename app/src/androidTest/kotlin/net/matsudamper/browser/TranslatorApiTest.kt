package net.matsudamper.browser

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ページから Translator and Language Detector APIs のポリフィルを使えることを確認する。
 *
 * ポリフィルは拡張機能・アプリ・Gecko 内蔵エンジンをまたいで動くため、実際の GeckoView 上で
 * ページから呼んで結果が返るところまでを通す。翻訳はモデルのダウンロードで外部ネットワークに
 * 依存するため、端末内で完結する言語判定で確認する。
 */
@RunWith(AndroidJUnit4::class)
class TranslatorApiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var localHttpServer: LocalHttpServer? = null

    @After
    fun tearDown() {
        localHttpServer?.close()
        localHttpServer = null
    }

    @Test
    fun languageDetectorDetectsLanguageFromPage() {
        composeRule.waitForBrowserReady()
        val pageUrl = startLocalPageServer()
        composeRule.openUrlFromUrlBar(pageUrl)

        val detected = runCatching {
            composeRule.waitForUrlBarContains(DETECTED_ENGLISH_MARKER, timeoutMillis = DETECTION_TIMEOUT_MS)
        }
        if (detected.isFailure) {
            // ページは進んだ段階を URL へ書き出すため、URL とログで止まった箇所を切り分けられる
            throw AssertionError(
                "言語判定の結果がページへ返らなかった: url=\"${composeRule.currentPageUrlFromUi()}\"\n" +
                    "logcat:\n${readTranslatorApiLogcat()}",
                detected.exceptionOrNull(),
            )
        }
    }

    private fun startLocalPageServer(): String {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val pageDir = File(targetContext.cacheDir, LOCAL_PAGE_DIR_NAME).apply { mkdirs() }
        File(pageDir, LOCAL_PAGE_FILE_NAME).writeText(LANGUAGE_DETECTION_PAGE)
        val server = LocalHttpServer(pageDir)
        localHttpServer = server
        return server.url(LOCAL_PAGE_FILE_NAME)
    }

    private fun readTranslatorApiLogcat(): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("logcat -d")
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
            .lineSequence()
            .filter { line -> LOGCAT_KEYWORDS.any { keyword -> line.contains(keyword, ignoreCase = true) } }
            .toList()
            .takeLast(LOGCAT_MAX_LINES)
            .joinToString(separator = "\n")
    }

    companion object {
        private const val LOCAL_PAGE_DIR_NAME = "translator-api"
        private const val LOCAL_PAGE_FILE_NAME = "translator_api_test.html"
        private const val DETECTED_ENGLISH_MARKER = "detected-en"
        private const val DETECTION_TIMEOUT_MS = 90_000L
        private const val LOGCAT_MAX_LINES = 80
        private val LOGCAT_KEYWORDS = listOf("TranslatorApi", "translator-api", "translator_api", "browsemTranslation")

        private val LANGUAGE_DETECTION_PAGE = """
            <html><body><script>
            (async () => {
              const report = (stage) => { location.hash = stage; };
              try {
                report("secure-" + isSecureContext + "-detector-" + (typeof LanguageDetector) + "-translator-" + (typeof Translator));
                const availability = await LanguageDetector.availability();
                report("availability-" + availability);
                const detector = await LanguageDetector.create();
                report("created");
                const results = await detector.detect(
                  "This is an English sentence written to check that the language detector works on this device."
                );
                report("detected-" + results[0].detectedLanguage);
              } catch (error) {
                report("error-at-" + location.hash.slice(1) + "-" + encodeURIComponent(error.name + ": " + error.message));
              }
            })();
            </script></body></html>
        """.trimIndent()
    }
}
