package net.matsudamper.browser

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView

/**
 * ページ下部の入力欄にフォーカスしてキーボードを表示したとき、
 * 入力欄がキーボードに隠れないことを確認する (Issue #674)。
 *
 * ビューポート下端付近に入力欄を置いたローカルページの入力欄にフォーカスし、
 * IME 表示後に入力欄のアクセシビリティノードの画面座標がキーボード上端より
 * 上にあることを検証する。JS の visualViewport はキーボード高さが Gecko に
 * 伝わっていない場合でも縮まないため、画面座標で判定する。
 *
 * テストページは入力欄を文書の末尾に置き、その上に固定 px の長い余白を敷いて
 * ある。下に余白が残っているとスクロールの余地があり、表示領域が縮まなくても
 * Gecko が入力欄を上へ運べてしまう。Issue #674 は「下までスクロールしても
 * 入力欄がキーボードに隠れたまま」という状態なので、スクロール上限で入力欄が
 * 画面下端に来る配置にする必要がある。
 */
@RunWith(AndroidJUnit4::class)
class KeyboardBottomInputTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var localHttpServer: LocalHttpServer? = null

    @Before
    fun setUp() {
        // aosp-atd イメージにはソフトキーボードが無いため、ダミー IME を有効化する
        assertTrue("テスト用 IME を既定にできない", TestIme.enable())
    }

    @After
    fun tearDown() {
        TestIme.reset()
        localHttpServer?.close()
        localHttpServer = null
    }

    /**
     * テスト用 IME が URL バーで表示できることを確認する切り分け用テスト。
     *
     * これが失敗する場合はダミー IME 自体の問題、成功する場合は
     * Gecko 側がキーボード表示を要求していないことになる。
     */
    @Test
    fun testImeShowsForUrlBar() {
        composeRule.waitUntil(timeoutMillis = URL_BAR_WAIT_MILLIS) {
            composeRule.onAllNodesWithTag(UrlTextInputTestTags.UrlBar.testTag)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()

        val shown = runCatching {
            composeRule.waitUntil(timeoutMillis = IME_WAIT_MILLIS) {
                imeInsetBottom() > 0
            }
            true
        }.getOrDefault(false)

        assertTrue("URL バーでもテスト用 IME が表示されない: ${TestIme.diagnostics()}", shown)
    }

    @Test
    fun bottomInputStaysVisibleAboveKeyboard() {
        val pageUrl = startBottomInputPageServer()
        composeRule.openLocalPageAndStabilize(pageUrl, BOTTOM_INPUT_FILE_NAME)
        // URL バーの IME が閉じきる前だと、その inset を「入力欄のキーボード」と
        // 誤認してページをタップしないまま先へ進んでしまう。
        assertTrue(
            "URL バーのキーボードが閉じない",
            waitForImeHidden(),
        )

        // 診断は失敗が確定してから取る。assertTrue の引数として渡すと再試行の前の
        // 状態しか残らず、実際に失敗した時点の dumpsys / logcat を確認できない。
        if (!focusBottomInputAndWaitForIme()) {
            throw AssertionError(
                "ページ下部の入力欄をフォーカスしてもキーボードが表示されない: ${TestIme.diagnostics()}",
            )
        }

        assertVisibleAboveKeyboard()
    }

    /**
     * position: fixed の下部入力欄がキーボードに隠れないことを検証する。
     *
     * chat 系 UI で使われる構成。固定配置の要素はスクロールしても動かないため、
     * 文書へ余白を足してスクロールさせる方式では救えない。表示領域そのものが
     * 縮む必要があることを検知する。
     */
    @Test
    fun fixedBottomInputStaysVisibleAboveKeyboard() {
        val pageUrl = startPageServer(FIXED_DIR_NAME, FIXED_ASSET_DIR)
        composeRule.openLocalPageAndStabilize(pageUrl, PAGE_FILE_NAME, timeoutMillis = URL_BAR_WAIT_MILLIS)
        assertTrue("URL バーのキーボードが閉じない", waitForImeHidden())

        if (!focusBottomInputAndWaitForIme()) {
            throw AssertionError(
                "固定配置の入力欄をフォーカスしてもキーボードが表示されない: ${TestIme.diagnostics()}",
            )
        }

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
        val keyboardHeight = waitForStableImeInsetBottom()
        assertTrue("キーボードの高さが安定しない", keyboardHeight > 0)
        // キーボードが画面の大半を覆うのはテスト用 IME の設定ミスであり、
        // Issue #674 の判定として意味を成さないため区別して落とす。
        assertTrue(
            "キーボードが画面の大半を覆っている: keyboardHeight=$keyboardHeight screenHeight=${screenHeight()}",
            keyboardHeight < screenHeight() / 2,
        )

        var lastDiagnostics = "入力欄のアクセシビリティノードが見つからない"
        var okCount = 0
        val deadline = SystemClock.elapsedRealtime() + INPUT_VISIBLE_WAIT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val bounds = findFocusedPageInputBoundsInScreen()
            val keyboardTop = keyboardTopInScreen()
            val geckoBottom = geckoViewBottomInScreen()
            val visibleBottom = listOfNotNull(keyboardTop, geckoBottom).minOrNull()
            if (bounds == null || visibleBottom == null) {
                lastDiagnostics = "bounds=$bounds keyboardTop=$keyboardTop geckoBottom=$geckoBottom"
                okCount = 0
            } else {
                lastDiagnostics =
                    "入力欄 bounds=$bounds キーボード上端=$keyboardTop GeckoView下端=$geckoBottom 画面高さ=${screenHeight()}"
                okCount = if (bounds.bottom <= visibleBottom) okCount + 1 else 0
            }
            if (okCount >= REQUIRED_STABLE_COUNT) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }

        // アクセシビリティの座標更新が遅延し、GeckoView は縮小済みなのに bounds だけ古い
        // 値のまま残ることがある。表示領域がキーボード上端まで縮んでいれば Issue #674 は満たしている。
        val geckoBottom = geckoViewBottomInScreen()
        val keyboardTop = keyboardTopInScreen()
        if (
            geckoBottom != null &&
            keyboardTop != null &&
            kotlin.math.abs(geckoBottom - keyboardTop) <= GECKO_KEYBOARD_TOLERANCE_PX &&
            findFocusedPageInputBoundsInScreen() != null
        ) {
            return
        }

        throw AssertionError(
            "キーボード表示中にページ下部の入力欄がキーボードに隠れている: $lastDiagnostics\n" +
                "GeckoView: ${geckoViewBoundsInScreen()}\n" +
                "編集可能ノード一覧:\n${dumpEditableNodes()}",
        )
    }

    /**
     * ページ内の入力欄をアクセシビリティ操作でフォーカスさせる。
     *
     * GeckoView の座標タップは Gecko 側の入力欄にフォーカスが移らないことがあり
     * (dumpsys 上で mInputShown=false のまま)、キーボードが出ないため、
     * ページ内の入力欄ノードを直接クリックする。
     */
    private fun clickPageInput(): Boolean {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = uiAutomation.rootInActiveWindow ?: return false
        var clicked = false
        try {
            val node = findPageInput(root)
            if (node != null) {
                // ACTION_CLICK が true を返してもフォーカスが移らないことがある。
                // 短絡させず両方を試す。
                val focused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val tapped = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                clicked = focused || tapped
                node.recycle()
            }
        } finally {
            root.recycle()
        }
        composeRule.waitForIdle()
        return clicked
    }

    /**
     * ページ内 (URL バー以外) の入力欄ノードを探す。
     */
    private fun findPageInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val isInput = node.isEditable || className.contains("EditText", ignoreCase = true)
        val isChrome = viewId.contains("UrlTextInput", ignoreCase = true)
        if (isInput && !isChrome) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findPageInput(child)
            child.recycle()
            if (found != null) return found
        }
        return null
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
            clickPageInput()
            // URL バーの IME 非表示が遅れて届くと、その inset だけで成立してしまう。
            // ページ内の入力欄がフォーカスされていることも条件に含める。
            val focused = runCatching {
                composeRule.waitUntil(timeoutMillis = TAP_RETRY_INTERVAL_MILLIS) {
                    imeInsetBottom() > 0 && findFocusedPageInputBoundsInScreen() != null
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
     * GeckoView の画面座標を返す。表示領域の縮小が効いているかの切り分けに使う。
     */
    private fun geckoViewBoundsInScreen(): String {
        var result = "見つからない"
        composeRule.runOnIdle {
            val geckoView = findGeckoView(composeRule.activity.window.decorView)
            if (geckoView != null) {
                val location = IntArray(2)
                geckoView.getLocationOnScreen(location)
                result = "top=${location[1]} bottom=${location[1] + geckoView.height}"
            }
        }
        return result
    }

    /**
     * View 階層から [GeckoView] を探す。
     */
    private fun findGeckoView(view: View): GeckoView? {
        if (view is GeckoView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findGeckoView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    /**
     * decorView から見た画面の高さを返す。
     */
    private fun screenHeight(): Int {
        var height = 0
        composeRule.runOnIdle {
            height = composeRule.activity.window.decorView.height
        }
        return height
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
     * GeckoView の下端の画面座標を返す。表示領域の縮小判定に使う。
     */
    private fun geckoViewBottomInScreen(): Int? {
        var bottom: Int? = null
        composeRule.runOnIdle {
            val geckoView = findGeckoView(composeRule.activity.window.decorView) ?: return@runOnIdle
            val location = IntArray(2)
            geckoView.getLocationOnScreen(location)
            bottom = location[1] + geckoView.height
        }
        return bottom
    }

    /**
     * Gecko コンテンツ内でフォーカス中の入力欄の画面座標を返す。
     *
     * ページ内の入力欄は Compose のセマンティクスに現れないため、
     * GeckoView が公開するアクセシビリティノードから探す。
     */
    private fun findFocusedPageInputBoundsInScreen(): Rect? {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = uiAutomation.rootInActiveWindow ?: return null
        try {
            val node = findFocusedPageInput(root) ?: return null
            node.refresh()
            if (!node.isFocused) {
                node.recycle()
                return null
            }
            return Rect().also { node.getBoundsInScreen(it) }.also { node.recycle() }
        } finally {
            root.recycle()
        }
    }

    /**
     * ページ内 (URL バー以外) でフォーカス中の入力欄ノードを探す。
     */
    private fun findFocusedPageInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val isInput = node.isEditable || className.contains("EditText", ignoreCase = true)
        val isChrome = viewId.contains("UrlTextInput", ignoreCase = true)
        if (isInput && !isChrome && node.isFocused) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findFocusedPageInput(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    /**
     * Gecko コンテンツ内でフォーカス中の入力欄の画面座標を返す (診断用)。
     */
    private fun findBottomInputBoundsInScreen(): Rect? = findFocusedPageInputBoundsInScreen()

    /**
     * 失敗時の診断用に、見つかった編集可能ノードを 1 行ずつ書き出す。
     */
    private fun dumpEditableNodes(): String {
        val nodes = collectEditableNodes()
        if (nodes.isEmpty()) return "(編集可能ノードなし)"
        return nodes.joinToString(separator = "\n") {
            "class=${it.className} focused=${it.focused} bounds=${it.bounds} text=${it.text}"
        }
    }

    /**
     * アクセシビリティツリーから編集可能なノードを集める。
     */
    private fun collectEditableNodes(): List<EditableNode> {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = uiAutomation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<EditableNode>()
        try {
            collectEditableNodes(root, result)
        } finally {
            root.recycle()
        }
        return result
    }

    private fun collectEditableNodes(node: AccessibilityNodeInfo, result: MutableList<EditableNode>) {
        if (node.isEditable) {
            // アクセシビリティノードはキャッシュから返るため、リフローで座標が動いても
            // イベントが飛ばない限り古い値のままになる。refresh はキャッシュを迂回して
            // 元のビューから読み直す。
            node.refresh()
            result += EditableNode(
                className = node.className?.toString().orEmpty(),
                focused = node.isFocused,
                bounds = Rect().also { node.getBoundsInScreen(it) },
                text = node.text?.toString().orEmpty().take(TEXT_DUMP_LENGTH),
            )
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                collectEditableNodes(child, result)
            } finally {
                child.recycle()
            }
        }
    }

    private data class EditableNode(
        val className: String,
        val focused: Boolean,
        val bounds: Rect,
        val text: String,
    )

    /**
     * 下部入力欄テスト用のローカル HTML をキャッシュへ展開し、
     * ループバック HTTP サーバーから配信してその URL を返す。
     */
    private fun startBottomInputPageServer(): String {
        return startPageServer(BOTTOM_INPUT_DIR_NAME, BOTTOM_INPUT_ASSET_DIR)
    }

    /**
     * assets の HTML をキャッシュへ展開し、ループバック HTTP サーバーから配信する。
     */
    private fun startPageServer(dirName: String, assetDir: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val destinationDir = File(instrumentation.targetContext.cacheDir, dirName)
            .apply { mkdirs() }
        val destination = File(destinationDir, PAGE_FILE_NAME)
        instrumentation.context.assets.open("$assetDir/$PAGE_FILE_NAME")
            .use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        val server = LocalHttpServer(destinationDir)
        localHttpServer = server
        return server.url(PAGE_FILE_NAME)
    }

    companion object {
        private const val PAGE_FILE_NAME = "index.html"
        private const val BOTTOM_INPUT_ASSET_DIR = "test-ime-bottom"
        private const val BOTTOM_INPUT_DIR_NAME = "test-ime-bottom"
        private const val BOTTOM_INPUT_FILE_NAME = PAGE_FILE_NAME
        private const val FIXED_ASSET_DIR = "test-ime-fixed"
        private const val FIXED_DIR_NAME = "test-ime-fixed"

        private const val URL_BAR_WAIT_MILLIS = 60_000L
        private const val IME_HIDE_WAIT_MILLIS = 10_000L
        private const val IME_WAIT_MILLIS = 30_000L
        private const val IME_STABLE_WAIT_MILLIS = 10_000L
        private const val INPUT_VISIBLE_WAIT_MILLIS = 20_000L

        /** タップが空振りしたときに再タップするまでの待ち時間 */
        private const val TAP_RETRY_INTERVAL_MILLIS = 3_000L

        private const val POLL_INTERVAL_MILLIS = 200L

        /** 診断出力に含めるテキストの最大長 */
        private const val TEXT_DUMP_LENGTH = 40

        /** IME アニメーション途中のフレームで判定しないよう、連続で満たすことを求める回数 */
        private const val REQUIRED_STABLE_COUNT = 5

        /** GeckoView 下端とキーボード上端の許容誤差 (px) */
        private const val GECKO_KEYBOARD_TOLERANCE_PX = 8
    }
}
