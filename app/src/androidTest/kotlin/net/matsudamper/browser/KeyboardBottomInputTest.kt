package net.matsudamper.browser

import android.graphics.Rect
import android.os.SystemClock
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
        // URL バーの IME が閉じきる前だと、その inset を「入力欄のキーボード」と
        // 誤認してページをタップしないまま先へ進んでしまう。
        assertTrue(
            "URL バーのキーボードが閉じない",
            waitForImeHidden(),
        )

        assertTrue(
            "ページ下部の入力欄をタップしてもキーボードが表示されない",
            focusBottomInputAndWaitForIme(),
        )

        assertVisibleAboveKeyboard()
    }

    /**
     * 入力欄がキーボード上端より上にある状態が続くことを検証する。
     *
     * IME はアニメーションで迫り上がるため、inset が初めて 0 を超えた瞬間に判定すると
     * まだキーボードが入力欄より下にある途中フレームで成立してしまう。inset が安定して
     * から、さらに一定回数連続で条件を満たすことを確認する。
     */
    private fun assertVisibleAboveKeyboard() {
        assertTrue(
            "キーボードの高さが安定しない",
            waitForStableImeInsetBottom() > 0,
        )

        var lastDiagnostics = "入力欄のアクセシビリティノードが見つからない"
        var okCount = 0
        val deadline = SystemClock.elapsedRealtime() + INPUT_VISIBLE_WAIT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val bounds = findBottomInputBoundsInScreen()
            val keyboardTop = keyboardTopInScreen()
            if (bounds == null || keyboardTop == null) {
                lastDiagnostics = "bounds=$bounds keyboardTop=$keyboardTop"
                okCount = 0
            } else {
                lastDiagnostics = "入力欄 bottom=${bounds.bottom} キーボード上端=$keyboardTop"
                okCount = if (bounds.bottom <= keyboardTop) okCount + 1 else 0
            }
            if (okCount >= REQUIRED_STABLE_COUNT) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }

        throw AssertionError(
            "キーボード表示中にページ下部の入力欄がキーボードに隠れている: $lastDiagnostics",
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
     * IME が閉じるまで待機し、閉じたかどうかを返す。
     */
    private fun waitForImeHidden(): Boolean {
        return runCatching {
            composeRule.waitUntil(timeoutMillis = IME_HIDE_WAIT_MILLIS) {
                imeInsetBottom() == 0
            }
            true
        }.getOrDefault(false)
    }

    /**
     * ページ内の入力欄にフォーカスが移り IME が表示されるまでタップを繰り返す。
     *
     * ページの読み込みや Gecko の解析が遅れると、URL 更新後もまだ入力欄が
     * 生成されておらずタップが空振りする。1 回だけのタップだと Issue #674 と
     * 無関係に失敗するため、条件を満たすまでタップを再試行する。
     */
    private fun focusBottomInputAndWaitForIme(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + IME_WAIT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            tapBottomOfGeckoContainer()
            val focused = runCatching {
                composeRule.waitUntil(timeoutMillis = TAP_RETRY_INTERVAL_MILLIS) {
                    imeInsetBottom() > 0 && findBottomInputBoundsInScreen() != null
                }
                true
            }.getOrDefault(false)
            if (focused) return true
        }
        return false
    }

    /**
     * IME の高さが変化しなくなるまで待ち、その高さを返す。安定しなければ 0 を返す。
     */
    private fun waitForStableImeInsetBottom(): Int {
        var lastValue = -1
        var stableCount = 0
        val deadline = SystemClock.elapsedRealtime() + IME_STABLE_WAIT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = imeInsetBottom()
            if (current > 0 && current == lastValue) {
                stableCount++
                if (stableCount >= REQUIRED_STABLE_COUNT) return current
            } else {
                stableCount = 0
            }
            lastValue = current
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return 0
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
        // フォーカス中の入力欄が複数見つかった場合は最も下にあるものを使う
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

        private const val IME_HIDE_WAIT_MILLIS = 10_000L
        private const val IME_WAIT_MILLIS = 30_000L
        private const val IME_STABLE_WAIT_MILLIS = 10_000L
        private const val INPUT_VISIBLE_WAIT_MILLIS = 15_000L

        /** タップが空振りしたときに再タップするまでの待ち時間 */
        private const val TAP_RETRY_INTERVAL_MILLIS = 3_000L

        private const val POLL_INTERVAL_MILLIS = 200L

        /** IME アニメーション途中のフレームで判定しないよう、連続で満たすことを求める回数 */
        private const val REQUIRED_STABLE_COUNT = 5
    }
}
