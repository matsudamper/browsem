package net.matsudamper.browser

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.matsudamper.browser.screen.browser.SimpleViewScreenTestTags
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * シンプル表示 (Readability WebExtension) の動作確認テスト。
 *
 * ローカルHTMLを記事ページとして開き、メニューから「シンプル表示」をタップすると
 * SimpleViewScreen オーバーレイが表示されることを確認する。
 */
@RunWith(AndroidJUnit4::class)
class SimpleViewTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * 記事ページでシンプル表示をタップすると SimpleViewScreen が表示されることを確認する。
     */
    @Test
    fun tappingSimpleViewMenuShowsSimpleViewScreen() {
        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)
        val articlePageUri = prepareLocalArticlePageUri()

        // Readability 拡張がインストール完了するまで待機
        waitForReadabilityExtensionInstalled()

        // 記事ページを読み込む
        composeRule.runOnIdle {
            activeTab.session.loadUri(articlePageUri)
        }

        // ページ読み込み完了を待機
        waitForActiveTabUrl(timeoutMillis = 60_000, activeTab = activeTab) { currentUrl ->
            currentUrl.startsWith("file:") && currentUrl.contains(LOCAL_READABILITY_INDEX_FILE_NAME)
        }

        // document_idle が発火してコンテンツスクリプトがポートを確立するまで待機
        // （ローカルファイルは通常すぐに読み込まれるが、念のため少し待つ）
        Thread.sleep(3_000)

        // メニューを開く
        composeRule.onNodeWithContentDescription("Menu").performClick()

        // 「シンプル表示」をタップ
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("シンプル表示").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("シンプル表示").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("シンプル表示").performClick()

        // SimpleViewScreen が表示されるまで待機
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(SimpleViewScreenTestTags.SimpleView.testTag).fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(
            "シンプル表示画面が表示されていない",
            composeRule.onAllNodesWithTag(SimpleViewScreenTestTags.SimpleView.testTag).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /**
     * シンプル表示を閉じると SimpleViewScreen が消えることを確認する。
     */
    @Test
    fun closingSimpleViewDismissesOverlay() {
        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)
        val articlePageUri = prepareLocalArticlePageUri()

        waitForReadabilityExtensionInstalled()

        composeRule.runOnIdle {
            activeTab.session.loadUri(articlePageUri)
        }

        waitForActiveTabUrl(timeoutMillis = 60_000, activeTab = activeTab) { currentUrl ->
            currentUrl.startsWith("file:") && currentUrl.contains(LOCAL_READABILITY_INDEX_FILE_NAME)
        }

        Thread.sleep(3_000)

        // シンプル表示を開く
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("シンプル表示").performClick()

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(SimpleViewScreenTestTags.SimpleView.testTag).fetchSemanticsNodes().isNotEmpty()
        }

        // 閉じるボタンをタップ
        composeRule.onNodeWithContentDescription("シンプル表示を閉じる").performClick()

        // SimpleViewScreen が消えるまで待機
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(SimpleViewScreenTestTags.SimpleView.testTag).fetchSemanticsNodes().isEmpty()
        }

        assertTrue(
            "シンプル表示画面が閉じられていない",
            composeRule.onAllNodesWithTag(SimpleViewScreenTestTags.SimpleView.testTag).fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun prepareLocalArticlePageUri(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val destinationDir = File(targetContext.cacheDir, LOCAL_READABILITY_DIR_NAME).apply { mkdirs() }
        val assetManager = instrumentation.context.assets
        val destination = File(destinationDir, LOCAL_READABILITY_INDEX_FILE_NAME)
        assetManager.open("$LOCAL_READABILITY_ASSET_DIR/$LOCAL_READABILITY_INDEX_FILE_NAME").use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return destination.toURI().toString()
    }

    private fun waitForReadabilityExtensionInstalled() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var installed = false
            composeRule.runOnIdle {
                installed = runCatching {
                    getBrowserViewModel().readabilityWebExtension.isInstalled()
                }.getOrDefault(false)
            }
            installed
        }
    }

    private fun waitForBrowserSessionController(): BrowserSessionController {
        var controller: BrowserSessionController? = null
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var resolved = false
            composeRule.runOnIdle {
                resolved = runCatching {
                    controller = getBrowserViewModel().browserSessionController
                }.isSuccess
            }
            resolved
        }
        return requireNotNull(controller)
    }

    private fun waitForActiveTab(browserSessionController: BrowserSessionController): BrowserTab {
        var activeTab: BrowserTab? = null
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var found = false
            composeRule.runOnIdle {
                activeTab = browserSessionController.tabs.firstOrNull { it.session.isOpen }
                    ?: browserSessionController.tabs.lastOrNull()
                found = activeTab != null
            }
            found
        }
        return requireNotNull(activeTab)
    }

    private fun waitForActiveTabUrl(
        timeoutMillis: Long,
        activeTab: BrowserTab,
        predicate: (String) -> Boolean,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            var matched = false
            composeRule.runOnIdle {
                matched = predicate(activeTab.currentUrl)
            }
            matched
        }
    }

    private fun getBrowserViewModel(): BrowserViewModel {
        return ViewModelProvider(composeRule.activity)[BrowserViewModel::class.java]
    }

    companion object {
        private const val LOCAL_READABILITY_ASSET_DIR = "test-readability"
        private const val LOCAL_READABILITY_DIR_NAME = "test-readability"
        private const val LOCAL_READABILITY_INDEX_FILE_NAME = "index.html"
    }
}
