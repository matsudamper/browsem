package net.matsudamper.browser

import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.File
import net.matsudamper.browser.ui.tabs.TabsScreenTestTags
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView

/**
 * キーボード表示時にページ下部の入力欄が隠れる不具合の再現テスト。
 *
 * Issue: https://github.com/matsudamper/browsem/issues/674
 *
 * 期待: IME 表示中、GeckoView の下端がキーボード上端より上にあり、
 * 下部固定入力欄がキーボードに隠れないこと。
 */
@RunWith(AndroidJUnit4::class)
class KeyboardBottomInputVisibilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var localHttpServer: LocalHttpServer? = null

    @After
    fun tearDown() {
        localHttpServer?.close()
        localHttpServer = null
    }

    @Test
    fun bottomFixedInputStaysAboveKeyboardWhenFocused() {
        ensureBrowserScreen()
        val pageUrl = startBottomInputPageServer()

        openLocalPage(
            url = pageUrl,
            urlMarker = INDEX_FILE_NAME,
        )
        composeRule.waitForUrlBarNotFocused(timeoutMillis = 30_000)

        focusBottomInputField()
        waitForImeVisible(timeoutMillis = 20_000)
        waitForImeLayoutSettled(timeoutMillis = 15_000)

        val snapshot = readImeLayoutSnapshot()
        assertTrue(
            "IME が表示されていない (snapshot=$snapshot)",
            snapshot.imeVisible || snapshot.imeInsetBottom > 0,
        )
        assertTrue(
            "GeckoView の下端がキーボード上端より下にある = 下部入力が隠れる " +
                "(geckoBottom=${snapshot.geckoBottomOnScreen}, keyboardTop=${snapshot.keyboardTopOnScreen}, " +
                "imeInset=${snapshot.imeInsetBottom}, geckoBottomMargin=${snapshot.geckoBottomMargin}, " +
                "snapshot=$snapshot)",
            snapshot.geckoBottomOnScreen <= snapshot.keyboardTopOnScreen + LAYOUT_TOLERANCE_PX,
        )
    }

    private fun focusBottomInputField() {
        val uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val inputBounds = Rect()
        var lastDump = ""
        try {
            composeRule.waitUntil(timeoutMillis = 30_000) {
                val field = uiDevice.findObject(By.hint(BOTTOM_INPUT_LABEL))
                    ?: uiDevice.findObject(By.desc(BOTTOM_INPUT_LABEL))
                if (field != null) {
                    field.click()
                    composeRule.waitForIdle()
                    true
                } else {
                    val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
                    val root = uiAutomation.rootInActiveWindow ?: return@waitUntil false
                    try {
                        val dump = StringBuilder()
                        val target = findBottomInputField(root, dump)
                        lastDump = dump.toString()
                        if (target == null) {
                            false
                        } else {
                            target.getBoundsInScreen(inputBounds)
                            target.recycle()
                            tapBottomInputOnGeckoContainer(inputBounds)
                            true
                        }
                    } finally {
                        root.recycle()
                    }
                }
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "下部入力欄をタップできない (dump=\n$lastDump)",
                e,
            )
        }
    }

    private fun tapBottomInputOnGeckoContainer(inputBounds: Rect) {
        val geckoSemantics = composeRule.onNodeWithTag(GeckoBrowserTabTestTags.GeckoContainer.testTag)
            .fetchSemanticsNode()
        val containerBounds = geckoSemantics.boundsInRoot
        val tapX = inputBounds.exactCenterX() - containerBounds.left
        val tapY = inputBounds.exactCenterY() - containerBounds.top
        composeRule.onNodeWithTag(GeckoBrowserTabTestTags.GeckoContainer.testTag)
            .performTouchInput {
                click(androidx.compose.ui.geometry.Offset(tapX, tapY))
            }
        composeRule.waitForIdle()
    }

    private fun findBottomInputField(
        node: AccessibilityNodeInfo,
        dump: StringBuilder,
    ): AccessibilityNodeInfo? {
        val cls = node.className?.toString().orEmpty()
        val text = node.text?.toString().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString().orEmpty()
        } else {
            ""
        }
        val contentDescription = node.contentDescription?.toString().orEmpty()
        val isEdit = node.isEditable || cls.contains("EditText", ignoreCase = true)
        if (isEdit) {
            dump.appendLine("edit cls=$cls text=$text hint=$hint desc=$contentDescription")
            if (
                text.contains(BOTTOM_INPUT_PLACEHOLDER, ignoreCase = true) ||
                hint.contains(BOTTOM_INPUT_PLACEHOLDER, ignoreCase = true) ||
                hint.contains(BOTTOM_INPUT_LABEL, ignoreCase = true) ||
                contentDescription.contains(BOTTOM_INPUT_LABEL, ignoreCase = true)
            ) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                findBottomInputField(child, dump)?.let { return it }
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private fun waitForImeVisible(timeoutMillis: Long) {
        try {
            composeRule.waitUntil(timeoutMillis = timeoutMillis) {
                val snapshot = readImeLayoutSnapshot()
                snapshot.imeVisible || snapshot.imeInsetBottom > 0
            }
        } catch (e: ComposeTimeoutException) {
            val snapshot = readImeLayoutSnapshot()
            throw AssertionError(
                "IME が表示されない (snapshot=$snapshot)",
                e,
            )
        }
    }

    private fun waitForImeLayoutSettled(timeoutMillis: Long) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            val snapshot = readImeLayoutSnapshot()
            snapshot.imeInsetBottom > 0
        }
        composeRule.waitForIdle()
    }

    private fun readImeLayoutSnapshot(): ImeLayoutSnapshot {
        val snapshotHolder = arrayOf<ImeLayoutSnapshot?>(null)
        composeRule.runOnIdle {
            val activity = composeRule.activity
            val decorView = activity.window.decorView
            val rootInsets = decorView.rootWindowInsets
            val imeVisible = rootInsets?.isVisible(WindowInsets.Type.ime()) == true
            val imeInsetBottom = rootInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
            val geckoView = requireNotNull(decorView.findGeckoView()) {
                "GeckoView が見つからない"
            }
            val location = IntArray(2)
            geckoView.getLocationOnScreen(location)
            val geckoBottomOnScreen = location[1] + geckoView.height
            val geckoBottomMargin = (geckoView.layoutParams as? ViewGroup.MarginLayoutParams)
                ?.bottomMargin ?: 0
            val screenHeight = activity.resources.displayMetrics.heightPixels
            val keyboardTopOnScreen = screenHeight - imeInsetBottom
            snapshotHolder[0] = ImeLayoutSnapshot(
                imeVisible = imeVisible,
                imeInsetBottom = imeInsetBottom,
                geckoBottomOnScreen = geckoBottomOnScreen,
                geckoBottomMargin = geckoBottomMargin,
                keyboardTopOnScreen = keyboardTopOnScreen,
                screenHeight = screenHeight,
            )
        }
        return requireNotNull(snapshotHolder[0])
    }

    private fun ensureBrowserScreen() {
        val browserReady = runCatching {
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag(UrlTextInputTestTags.UrlBar.testTag)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            true
        }.getOrDefault(false)
        if (browserReady) return

        val tabsReady = runCatching {
            composeRule.waitForTabsScreenLoaded(timeoutMillis = 10_000)
            true
        }.getOrDefault(false)
        if (tabsReady) {
            composeRule.onNodeWithTag(TabsScreenTestTags.AddTabButton.testTag).performClick()
            composeRule.waitForIdle()
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(UrlTextInputTestTags.UrlBar.testTag)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openLocalPage(url: String, urlMarker: String) {
        composeRule.openUrlViaViewIntent(url)
        val openedByIntent = runCatching {
            composeRule.waitForUrlBarContains(urlMarker, timeoutMillis = 20_000)
            true
        }.getOrDefault(false)
        if (!openedByIntent) {
            composeRule.openUrlFromUrlBar(url)
            composeRule.waitForUrlBarContains(urlMarker, timeoutMillis = 60_000)
        }
    }

    private fun startBottomInputPageServer(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val destinationDir = File(targetContext.cacheDir, ASSET_DIR_NAME).apply { mkdirs() }
        val assetManager = instrumentation.context.assets
        val destination = File(destinationDir, INDEX_FILE_NAME)
        assetManager.open("$ASSET_DIR_NAME/$INDEX_FILE_NAME").use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val server = LocalHttpServer(destinationDir)
        localHttpServer = server
        return server.url(INDEX_FILE_NAME)
    }

    private fun View.findGeckoView(): GeckoView? {
        if (this is GeckoView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findGeckoView()?.let { return it }
        }
        return null
    }

    private data class ImeLayoutSnapshot(
        val imeVisible: Boolean,
        val imeInsetBottom: Int,
        val geckoBottomOnScreen: Int,
        val geckoBottomMargin: Int,
        val keyboardTopOnScreen: Int,
        val screenHeight: Int,
    )

    companion object {
        private const val ASSET_DIR_NAME = "test-keyboard-bottom-input"
        private const val INDEX_FILE_NAME = "index.html"
        private const val BOTTOM_INPUT_PLACEHOLDER = "Type here"
        private const val BOTTOM_INPUT_LABEL = "Bottom field"
        private const val LAYOUT_TOLERANCE_PX = 8
    }
}
