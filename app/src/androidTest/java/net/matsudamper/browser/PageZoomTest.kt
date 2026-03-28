package net.matsudamper.browser

import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * メニューの表示値変化と、GeckoView 内で更新される URL ハッシュ値を使って
 * ビューポート幅が変化することを確認する。
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
        composeRule.onNode(
            hasTestTag(BrowserToolbarTestTags.MenuButton.testTag)
                .and(hasParent(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag)))
        ).performClick()
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
        composeRule.onNode(
            hasTestTag(BrowserToolbarTestTags.MenuButton.testTag)
                .and(hasParent(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag)))
        ).performClick()
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
        composeRule.onNode(
            hasTestTag(BrowserToolbarTestTags.MenuButton.testTag)
                .and(hasParent(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag)))
        ).performClick()
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
        composeRule.onNode(
            hasTestTag(BrowserToolbarTestTags.MenuButton.testTag)
                .and(hasParent(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag)))
        ).performClick()
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
     * 200%ズーム後にページ内タップで得た innerWidth が初期値より小さくなることを確認する。
     */
    @Test
    fun pageZoomInNarrowsViewportInnerWidth() {
        val zoomPageUri = prepareLocalZoomPageUri()
        composeRule.openUrlViaViewIntent(zoomPageUri)
        composeRule.waitForUrlBarContains(ZOOM_INDEX_FILE_NAME, timeoutMillis = 60_000)

        val initialWidth = readInnerWidthFromUrlHash()
        assertTrue("初期 window.innerWidth が取得できなかった (got $initialWidth)", initialWidth > 0)

        openPageZoomMenuAndSet200Percent()
        pressSystemBack()
        Thread.sleep(1_000)

        val zoomedWidth = readInnerWidthFromUrlHash()
        assertTrue("200% ズーム後の window.innerWidth が取得できなかった (got $zoomedWidth)", zoomedWidth > 0)
        assertTrue(
            "ズームイン後に viewport 幅が縮小していない (initial=$initialWidth, zoomed=$zoomedWidth)",
            zoomedWidth < initialWidth,
        )
    }

    /**
     * ズーム後に再読み込みしても、ページズーム表示値が維持されることを確認する。
     */
    @Test
    fun pageZoomPersistedAfterNavigation() {
        val zoomPageUri = prepareLocalZoomPageUri()
        composeRule.openUrlViaViewIntent(zoomPageUri)
        composeRule.waitForUrlBarContains(ZOOM_INDEX_FILE_NAME, timeoutMillis = 60_000)

        openPageZoomMenuAndSet200Percent()
        composeRule.onNodeWithText("再読み込み").performClick()
        composeRule.waitForUrlBarContains(ZOOM_INDEX_FILE_NAME, timeoutMillis = 60_000)

        composeRule.onNode(
            hasTestTag(BrowserToolbarTestTags.MenuButton.testTag)
                .and(hasParent(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag)))
        ).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("200%").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "ページ再読み込み後に200%が維持されていない",
            composeRule.onAllNodesWithText("200%").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    // ─── ヘルパー ───────────────────────────────────────────────────────────

    private fun openPageZoomMenuAndSet200Percent() {
        composeRule.onNode(
            hasTestTag(BrowserToolbarTestTags.MenuButton.testTag)
                .and(hasParent(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag)))
        ).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("ページズーム").fetchSemanticsNodes().isNotEmpty()
        }
        repeat(5) {
            composeRule.onNodeWithContentDescription("拡大").performClick()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("200%").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Gecko コンテナをタップして URL ハッシュへ埋め込まれた innerWidth を取得する。
     */
    private fun readInnerWidthFromUrlHash(): Int {
        val previous = composeRule.currentUrlBarText()
        composeRule.tapGeckoContainer()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            val current = composeRule.currentUrlBarText()
            current != previous && current.contains("#w=")
        }
        val current = composeRule.currentUrlBarText()
        val width = Regex("""[#&]w=(\d+)""")
            .find(current)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return width ?: -1
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

    private fun pressSystemBack() {
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
    }

    companion object {
        private const val ZOOM_ASSET_DIR = "test-zoom"
        private const val ZOOM_DIR_NAME = "test-zoom"
        private const val ZOOM_INDEX_FILE_NAME = "index.html"
    }
}
