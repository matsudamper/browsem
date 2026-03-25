package net.matsudamper.browser

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ページズーム機能（viewport width 操作）のインストルメンテーションテスト。
 *
 * メニューの表示値変化と、GeckoView 内の window.innerWidth が実際に
 * 縮小・拡大されることをエミュレータ上で確認する。
 */
@RunWith(AndroidJUnit4::class)
class PageZoomTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * 初期状態でページズームが100%であることを確認する。
     */
    @Test
    fun initialPageZoomIsHundredPercent() {
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("ページズーム").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "初期ページズームが100%でない",
            composeRule.onAllNodesWithText("100%").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /**
     * 「拡大」ボタンをタップするとページズームパーセントが上がることを確認する。
     */
    @Test
    fun pageZoomInIncreasesDisplayedPercent() {
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("ページズーム").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("拡大").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("110%").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "ズームイン後に110%が表示されない",
            composeRule.onAllNodesWithText("110%").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /**
     * 「縮小」ボタンをタップするとページズームパーセントが下がることを確認する。
     */
    @Test
    fun pageZoomOutDecreasesDisplayedPercent() {
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("ページズーム").fetchSemanticsNodes().isNotEmpty()
        }
        // まず拡大して110%にする
        composeRule.onNodeWithContentDescription("拡大").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("110%").fetchSemanticsNodes().isNotEmpty()
        }

        // 縮小して100%に戻す
        composeRule.onNodeWithContentDescription("縮小").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("100%").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "ズームアウト後に100%が表示されない",
            composeRule.onAllNodesWithText("100%").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /**
     * ズームパーセントボタンをタップするとズームが100%にリセットされることを確認する。
     */
    @Test
    fun tappingPercentButtonResetsZoomToHundred() {
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("ページズーム").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("拡大").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("110%").fetchSemanticsNodes().isNotEmpty()
        }

        // パーセントボタン（"110%"）をタップしてリセット
        composeRule.onNodeWithText("110%").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("100%").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "リセット後に100%が表示されない",
            composeRule.onAllNodesWithText("100%").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /**
     * ズームインすると GeckoView 内の window.innerWidth が縮小することを確認する。
     *
     * viewport width = 画面dp幅 / ズーム率 の式でビューポートを狭めることでコンテンツを
     * 大きく見せる（ズームイン）ため、200% 適用後の innerWidth は初期値より小さくなる。
     */
    @Test
    fun pageZoomInNarrowsViewportInnerWidth() {
        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)
        val zoomPageUri = prepareLocalZoomPageUri()

        composeRule.runOnIdle {
            activeTab.session.loadUri(zoomPageUri)
        }
        waitForActiveTabUrl(60_000, activeTab) { url ->
            url.startsWith("file:") && url.contains(ZOOM_INDEX_FILE_NAME)
        }

        // 初期 viewport 幅を取得
        val initialWidth = readInnerWidthViaTitle(activeTab, callId = 1)
        assertTrue("初期 window.innerWidth が取得できなかった (got $initialWidth)", initialWidth > 0)

        // メニューを開き 200% まで拡大（100→110→125→150→175→200 = 5ステップ）
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("ページズーム").fetchSemanticsNodes().isNotEmpty()
        }
        repeat(5) {
            composeRule.onNodeWithContentDescription("拡大").performClick()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("200%").fetchSemanticsNodes().isNotEmpty()
        }
        // メニューを閉じる
        pressSystemBack()

        // JS 実行と viewport 再計算の完了を待つ
        Thread.sleep(2_000)

        val zoomedWidth = readInnerWidthViaTitle(activeTab, callId = 2)
        assertTrue(
            "200% ズーム後の window.innerWidth が取得できなかった (got $zoomedWidth)",
            zoomedWidth > 0,
        )
        assertTrue(
            "ズームイン後に viewport 幅が縮小していない (initial=$initialWidth, zoomed=$zoomedWidth)",
            zoomedWidth < initialWidth,
        )
    }

    /**
     * ズームインした状態でページを再ナビゲートしても、ズームが維持されることを確認する。
     *
     * onPageStop でズームを再注入する実装が動作していることを検証する。
     */
    @Test
    fun pageZoomPersistedAfterNavigation() {
        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)
        val zoomPageUri = prepareLocalZoomPageUri()

        composeRule.runOnIdle {
            activeTab.session.loadUri(zoomPageUri)
        }
        waitForActiveTabUrl(60_000, activeTab) { url ->
            url.startsWith("file:") && url.contains(ZOOM_INDEX_FILE_NAME)
        }

        // 初期 viewport 幅を記録
        val initialWidth = readInnerWidthViaTitle(activeTab, callId = 1)
        assertTrue("初期 window.innerWidth が取得できなかった", initialWidth > 0)

        // 200% に拡大
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("ページズーム").fetchSemanticsNodes().isNotEmpty()
        }
        repeat(5) {
            composeRule.onNodeWithContentDescription("拡大").performClick()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("200%").fetchSemanticsNodes().isNotEmpty()
        }
        pressSystemBack()
        Thread.sleep(1_000)

        // 同じページを再ナビゲート（onPageStop でズームが再注入されることを確認）
        composeRule.runOnIdle {
            activeTab.session.loadUri(zoomPageUri)
        }
        // ハッシュなしのURLが来るまで待機（ページ再読み込み開始）
        waitForActiveTabUrl(60_000, activeTab) { url ->
            url.startsWith("file:") && url.contains(ZOOM_INDEX_FILE_NAME) && !url.contains("#")
        }
        // onPageStop の完了とズーム再注入の完了を待つ
        Thread.sleep(4_000)

        val afterNavigationWidth = readInnerWidthViaTitle(activeTab, callId = 3)
        assertTrue(
            "ナビゲーション後の window.innerWidth が取得できなかった (got $afterNavigationWidth)",
            afterNavigationWidth > 0,
        )
        assertTrue(
            "ページ遷移後にズームが維持されていない (initial=$initialWidth, afterNav=$afterNavigationWidth)",
            afterNavigationWidth < initialWidth,
        )
    }

    // ─── ヘルパー ───────────────────────────────────────────────────────────

    /**
     * `window.innerWidth` を document.title 経由で取得する。
     *
     * javascript: URI でタイトルをセットし、onTitleChange 経由で
     * activeTab.title に反映されるのを待って値を読み取る。
     * callId ごとに異なるセンチネル文字列を使い前回の値との混同を防ぐ。
     *
     * window.location.hash 方式は session.loadUri("javascript:...") 経由で実行した JS による
     * ハッシュ変更が onLocationChange を発火しないため使用できない。
     * document.title の変更は DOM 監視イベントであり onTitleChange が確実に発火する。
     */
    private fun readInnerWidthViaTitle(activeTab: BrowserTab, callId: Int): Int {
        val sentinel = "ZW$callId"
        composeRule.runOnIdle {
            activeTab.session.loadUri(
                "javascript:void(document.title='${sentinel}_' + window.innerWidth)",
            )
        }
        composeRule.waitUntil(timeoutMillis = 15_000) {
            var matched = false
            composeRule.runOnIdle {
                matched = activeTab.title.contains(sentinel)
            }
            matched
        }
        val width = activeTab.title.substringAfter("${sentinel}_", "")
        return width.toIntOrNull() ?: -1
    }

    /**
     * テスト用ローカル HTML をキャッシュへ展開し、file URI を返す。
     */
    private fun prepareLocalZoomPageUri(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val destinationDir = File(targetContext.cacheDir, ZOOM_DIR_NAME).apply { mkdirs() }
        val assetManager = instrumentation.context.assets
        val destination = File(destinationDir, ZOOM_INDEX_FILE_NAME)
        assetManager.open("$ZOOM_ASSET_DIR/$ZOOM_INDEX_FILE_NAME").use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return destination.toURI().toString()
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

    private fun pressSystemBack() {
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun getBrowserViewModel(): BrowserViewModel {
        return ViewModelProvider(composeRule.activity)[BrowserViewModel::class.java]
    }

    companion object {
        private const val ZOOM_ASSET_DIR = "test-zoom"
        private const val ZOOM_DIR_NAME = "test-zoom"
        private const val ZOOM_INDEX_FILE_NAME = "index.html"
    }
}
