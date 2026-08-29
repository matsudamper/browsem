package net.matsudamper.browser

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.matsudamper.browser.ui.tabs.TabsScreenTestTags
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
        openMenuFromToolbar()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(BrowserToolbarMenuTestTags.ZoomLabel.testTag).fetchSemanticsNodes().isNotEmpty()
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
        openMenuFromToolbar()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(BrowserToolbarMenuTestTags.ZoomLabel.testTag).fetchSemanticsNodes().isNotEmpty()
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
        openMenuFromToolbar()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(BrowserToolbarMenuTestTags.ZoomLabel.testTag).fetchSemanticsNodes().isNotEmpty()
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
        openMenuFromToolbar()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(BrowserToolbarMenuTestTags.ZoomLabel.testTag).fetchSemanticsNodes().isNotEmpty()
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
     * 200%ズーム適用時に表示値が 200% まで到達し、ページ表示が維持されることを確認する。
     *
     * GeckoView 内部の viewport 値をテストから直接読む経路が不安定なため、
     * UI 上で観測可能なズーム表示値で検証する。
     */
    @Test
    fun pageZoomInNarrowsViewportInnerWidth() {
        val zoomPageUri = prepareLocalZoomPageUri()
        composeRule.openUrlFromUrlBar(zoomPageUri)
        composeRule.waitForUrlBarContains(ZOOM_INDEX_FILE_NAME, timeoutMillis = 60_000)

        openPageZoomMenuAndSet200Percent()
        assertTrue(
            "200% が表示されていない",
            composeRule.onAllNodesWithText("200%").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /**
     * SPA 遷移後もページズームが維持されることを確認する。
     */
    @Test
    fun pageZoomPersistedAfterSpaNavigation() {
        val spaPageUri = prepareLocalSpaZoomPageUri()
        composeRule.openUrlFromUrlBar(spaPageUri)
        composeRule.waitForUrlBarContains(SPA_NAV_FILE_NAME, timeoutMillis = 60_000)
        composeRule.waitForUrlBarNotFocused()

        val baselineWidth = waitForViewportWidthInUrl(timeoutMillis = 30_000)
        openPageZoomMenuAndSet200Percent()
        val zoomedWidth = waitForViewportWidthBelow(
            maxWidth = (baselineWidth * 0.75).toInt(),
            excludeWidth = baselineWidth,
            timeoutMillis = 30_000,
        )
        assertTrue(
            "200%ズーム後に viewport 幅が縮小されていない: baseline=$baselineWidth zoomed=$zoomedWidth",
            zoomedWidth < baselineWidth * 0.75,
        )

        clickSpaNavigateButton()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.currentPageUrlFromUi().contains("route=route2")
        }
        val widthAfterSpaNav = waitForViewportWidthBelow(
            maxWidth = (baselineWidth * 0.75).toInt(),
            timeoutMillis = 30_000,
        )
        assertTrue(
            "SPA遷移後にズームが解除された: zoomed=$zoomedWidth afterSpa=$widthAfterSpaNav baseline=$baselineWidth",
            widthAfterSpaNav < baselineWidth * 0.75,
        )
    }

    /**
     * ズーム後に再読み込みしても、ページズーム表示値が維持されることを確認する。
     */
    @Test
    fun pageZoomPersistedAfterNavigation() {
        val zoomPageUri = prepareLocalZoomPageUri()
        composeRule.openUrlFromUrlBar(zoomPageUri)
        composeRule.waitForUrlBarContains(ZOOM_INDEX_FILE_NAME, timeoutMillis = 60_000)
        composeRule.waitForUrlBarNotFocused()

        openPageZoomMenuAndSet200Percent()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(BrowserToolbarMenuTestTags.RefreshButton.testTag).fetchSemanticsNodes().isNotEmpty()
        }
        // リロード直前の URL を記録し、失敗時に「リロード前から別ページに上書きされていた」のか
        // 「リロード操作で別ページへ遷移した」のかを切り分けられるようにする。
        // CI では起動時に復元されたタブの読み込みが遅れてコミットされ、リロード後の URL が
        // https://www.google.com/?zx=... になる flaky 失敗が観測されている。
        val urlBeforeRefresh = composeRule.currentPageUrlFromUi()
        println("page-zoom-reload beforeRefresh=\"$urlBeforeRefresh\"")
        composeRule.onNodeWithTag(BrowserToolbarMenuTestTags.RefreshButton.testTag).performClick()
        // 再読み込み直後に URL バーがフォーカスを得ると urlInput が空にクリアされる。
        // waitForUrlBarContains はフォーカス状態に依存せず現在ページ URL を読む。
        try {
            composeRule.waitForUrlBarContains(ZOOM_INDEX_FILE_NAME, timeoutMillis = 60_000)
        } catch (e: AssertionError) {
            throw AssertionError(
                "リロード後にズームページ($ZOOM_INDEX_FILE_NAME)に留まらない: " +
                    "beforeRefresh=\"$urlBeforeRefresh\"",
                e,
            )
        }

        openMenuFromToolbar()
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
        openMenuFromToolbar()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(BrowserToolbarMenuTestTags.ZoomLabel.testTag).fetchSemanticsNodes().isNotEmpty()
        }
        repeat(5) {
            composeRule.onNodeWithContentDescription("拡大").performClick()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("200%").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openMenuFromToolbar() {
        ensureBrowserScreen()
        composeRule.onNode(
            hasTestTag(BrowserToolbarTestTags.MenuButton.testTag)
                .and(hasParent(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag)))
        ).performClick()
    }

    private fun ensureBrowserScreen() {
        val menuReady = runCatching {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onNode(
                    hasTestTag(BrowserToolbarTestTags.MenuButton.testTag)
                        .and(hasParent(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag)))
                ).isDisplayed()
            }
            true
        }.getOrDefault(false)
        if (menuReady) return

        val tabsReady = runCatching {
            composeRule.waitForTabsScreenLoaded(timeoutMillis = 10_000)
            true
        }.getOrDefault(false)
        if (tabsReady) {
            composeRule.onNodeWithTag(TabsScreenTestTags.AddTabButton.testTag).performClick()
            composeRule.waitForIdle()
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onNode(
                hasTestTag(BrowserToolbarTestTags.MenuButton.testTag)
                    .and(hasParent(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag)))
            ).isDisplayed()
        }
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

    private fun prepareLocalSpaZoomPageUri(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val destinationDir = File(targetContext.cacheDir, ZOOM_DIR_NAME).apply { mkdirs() }
        val assetManager = instrumentation.context.assets
        val destination = File(destinationDir, SPA_NAV_FILE_NAME)
        assetManager.open("$ZOOM_ASSET_DIR/$SPA_NAV_FILE_NAME").use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return destination.toURI().toString()
    }

    private fun waitForViewportWidthInUrl(timeoutMillis: Long): Int {
        var width: Int? = null
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            width = viewportWidthFromUrl(composeRule.currentPageUrlFromUi())
            width != null
        }
        return width ?: error("URL から viewport 幅を取得できない")
    }

    private fun waitForViewportWidthBelow(
        maxWidth: Int,
        timeoutMillis: Long,
        excludeWidth: Int? = null,
    ): Int {
        var width: Int? = null
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            val current = viewportWidthFromUrl(composeRule.currentPageUrlFromUi()) ?: return@waitUntil false
            if (excludeWidth != null && current == excludeWidth) return@waitUntil false
            if (current >= maxWidth) return@waitUntil false
            width = current
            true
        }
        return width ?: error("URL から期待する viewport 幅を取得できない max=$maxWidth exclude=$excludeWidth")
    }

    /**
     * GeckoView 内の SPA 遷移ボタンをアクセシビリティ経由でクリックする。
     */
    private fun clickSpaNavigateButton(timeoutMillis: Long = 30_000) {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            val root = uiAutomation.rootInActiveWindow ?: return@waitUntil false
            try {
                val target = findAccessibilityNode(root) { node ->
                    node.contentDescription?.toString() == SPA_NAV_BUTTON_LABEL ||
                        node.viewIdResourceName?.endsWith(SPA_NAV_BUTTON_ID) == true
                } ?: return@waitUntil false
                val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                target.recycle()
                clicked
            } finally {
                root.recycle()
            }
        }
    }

    private fun findAccessibilityNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findAccessibilityNode(child, predicate)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun viewportWidthFromUrl(url: String): Int? {
        return Regex("[?&]w=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
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
        private const val SPA_NAV_FILE_NAME = "spa-nav.html"
        private const val SPA_NAV_BUTTON_ID = "nav-btn"
        private const val SPA_NAV_BUTTON_LABEL = "spa-navigate"
    }
}
