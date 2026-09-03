package net.matsudamper.browser

import android.graphics.Rect
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ページ下部の入力欄にフォーカスしてキーボードを表示したとき、
 * 入力欄がキーボードに隠れないことを確認する (Issue #674)。
 *
 * ビューポート最下部に入力欄を置いたローカルページをタップしてフォーカスし、
 * IME 表示後に入力欄のアクセシビリティノードの画面座標がキーボード上端より
 * 上にあることを検証する。JS の visualViewport はキーボード高さが Gecko に
 * 伝わっていない場合でも縮まないため、画面座標で判定する。
 */
@RunWith(AndroidJUnit4::class)
class KeyboardBottomInputTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var localHttpServer: LocalHttpServer? = null

    @After
    fun tearDown() {
        localHttpServer?.close()
        localHttpServer = null
    }

    @Test
    fun bottomInputStaysVisibleAboveKeyboard() {
        val pageUrl = startBottomInputPageServer()
        composeRule.openUrlFromUrlBar(pageUrl)
        composeRule.waitForUrlBarContains(BOTTOM_INPUT_FILE_NAME, timeoutMillis = 60_000)
        composeRule.waitForUrlBarNotFocused()

        tapBottomOfGeckoContainer()

        assertTrue(
            "ページ下部の入力欄をタップしてもキーボードが表示されない",
            waitForImeVisible(timeoutMillis = IME_WAIT_MILLIS),
        )

        var lastDiagnostics = "入力欄のアクセシビリティノードが見つからない"
        val visible = runCatching {
            composeRule.waitUntil(timeoutMillis = INPUT_VISIBLE_WAIT_MILLIS) {
                val bounds = findBottomInputBoundsInScreen()
                val keyboardTop = keyboardTopInScreen()
                if (bounds == null || keyboardTop == null) {
                    lastDiagnostics = "bounds=$bounds keyboardTop=$keyboardTop"
                    false
                } else {
                    lastDiagnostics = "入力欄 bottom=${bounds.bottom} キーボード上端=$keyboardTop"
                    bounds.bottom <= keyboardTop
                }
            }
            true
        }.getOrDefault(false)

        assertTrue(
            "キーボード表示中にページ下部の入力欄がキーボードに隠れている: $lastDiagnostics",
            visible,
        )
    }

    /**
     * GeckoView の下端付近 (入力欄の位置) をタップしてフォーカスさせる。
     */
    private fun tapBottomOfGeckoContainer() {
        composeRule.onNodeWithTag(GeckoBrowserTabTestTags.GeckoContainer.testTag)
            .performTouchInput {
                click(percentOffset(0.5f, BOTTOM_INPUT_TAP_RATIO))
            }
        composeRule.waitForIdle()
    }

    /**
     * IME が表示されるまで待機し、表示されたかどうかを返す。
     */
    private fun waitForImeVisible(timeoutMillis: Long): Boolean {
        return runCatching {
            composeRule.waitUntil(timeoutMillis = timeoutMillis) {
                imeInsetBottom() > 0
            }
            true
        }.getOrDefault(false)
    }

    private fun imeInsetBottom(): Int {
        var bottom = 0
        composeRule.runOnIdle {
            val insets: WindowInsets? = composeRule.activity.window.decorView.rootWindowInsets
            bottom = insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        }
        return bottom
    }

    /**
     * キーボード上端の画面座標を返す。IME 非表示なら null。
     */
    private fun keyboardTopInScreen(): Int? {
        val imeBottom = imeInsetBottom()
        if (imeBottom <= 0) return null
        var decorBottom = 0
        composeRule.runOnIdle {
            val decorView = composeRule.activity.window.decorView
            val location = IntArray(2)
            decorView.getLocationOnScreen(location)
            decorBottom = location[1] + decorView.height
        }
        return decorBottom - imeBottom
    }

    /**
     * Gecko コンテンツ内でフォーカス中の入力欄の画面座標を返す。
     *
     * ページ内の入力欄は Compose のセマンティクスに現れないため、
     * GeckoView が公開するアクセシビリティノードから探す。
     */
    private fun findBottomInputBoundsInScreen(): Rect? {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = uiAutomation.rootInActiveWindow ?: return null
        val editableBounds = mutableListOf<Rect>()
        try {
            collectEditableBounds(root, editableBounds)
        } finally {
            root.recycle()
        }
        // フォーカス中の入力欄が見つからない場合に備え、最も下にある入力欄を使う
        return editableBounds.maxByOrNull { it.bottom }
    }

    /**
     * フォーカス中の編集可能ノードの画面座標を集める。
     */
    private fun collectEditableBounds(node: AccessibilityNodeInfo, result: MutableList<Rect>) {
        if (node.isEditable && node.isFocused) {
            result += Rect().also { node.getBoundsInScreen(it) }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                collectEditableBounds(child, result)
            } finally {
                child.recycle()
            }
        }
    }

    /**
     * 下部入力欄テスト用のローカル HTML をキャッシュへ展開し、
     * ループバック HTTP サーバーから配信してその URL を返す。
     */
    private fun startBottomInputPageServer(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val destinationDir = File(instrumentation.targetContext.cacheDir, BOTTOM_INPUT_DIR_NAME)
            .apply { mkdirs() }
        val destination = File(destinationDir, BOTTOM_INPUT_FILE_NAME)
        instrumentation.context.assets.open("$BOTTOM_INPUT_ASSET_DIR/$BOTTOM_INPUT_FILE_NAME")
            .use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        val server = LocalHttpServer(destinationDir)
        localHttpServer = server
        return server.url(BOTTOM_INPUT_FILE_NAME)
    }

    companion object {
        private const val BOTTOM_INPUT_ASSET_DIR = "test-ime-bottom"
        private const val BOTTOM_INPUT_DIR_NAME = "test-ime-bottom"
        private const val BOTTOM_INPUT_FILE_NAME = "index.html"

        /** 入力欄はビューポート下端 12% を占めるため、その中央付近をタップする */
        private const val BOTTOM_INPUT_TAP_RATIO = 0.94f

        private const val IME_WAIT_MILLIS = 20_000L
        private const val INPUT_VISIBLE_WAIT_MILLIS = 10_000L
    }
}
