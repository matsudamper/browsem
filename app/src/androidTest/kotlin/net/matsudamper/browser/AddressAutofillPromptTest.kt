package net.matsudamper.browser

import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.OptIn
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.matsudamper.browser.data.address.AddressEntity
import net.matsudamper.browser.data.address.AddressRepository
import org.junit.After
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.ExperimentalGeckoViewApi
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoResult
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException

/**
 * GeckoView の住所フォーム自動入力 (formautofill) が実際に動作することを確認するテスト。
 *
 * AppModule で有効化した extensions.formautofill.addresses.capture.enabled により、
 * 住所フォーム送信時に PromptDelegate.onAddressSave が発火して保存ダイアログが表示されること、
 * 保存済み住所がある状態で GeckoView 本家の address_form.html と、
 * ユーザーが再現に使っている MDN autocomplete の実ページで
 * IME 直上の候補バーから選んだ住所がフォームへ入力されることを検証する。
 *
 * file:// ではフォーム送信が行われないことが CI の診断で判明したため、
 * ループバック HTTP サーバでページを配信する。
 */
@RunWith(AndroidJUnit4::class)
class AddressAutofillPromptTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var httpServer: LocalHttpServer? = null
    private val savedPrefs = mutableListOf<SavedPref>()
    private var seededAddresses = false

    @After
    fun tearDown() {
        restoreSavedPrefs()
        if (seededAddresses) {
            clearSeededAddresses()
            seededAddresses = false
        }
        httpServer?.close()
        httpServer = null
    }

    @Test
    fun focusingMdnAutocompleteSampleShowsAddressSelectDialog() {
        seedUserReportedAddressWithoutCountry()

        applyTestPrefsAndAwaitAddressAutofillEnabled()
        saveAndSetPref("geckoview.autocomplete.selection_dismiss_delay_ms", 60_000)
        clearLogcat()

        composeRule.openUrlFromUrlBar(MDN_AUTOCOMPLETE_PAGE_URL)
        composeRule.waitForUrlBarContains("autocomplete", timeoutMillis = 90_000)
        composeRule.waitForUrlBarNotFocused(timeoutMillis = 30_000)

        val fieldClick = clickMdnFamilyNameField()

        selectFirstAutofillSuggestion(
            extraMessage = "MDN の実ページで住所の候補バーが出ない\n" +
                "苗字欄クリック=$fieldClick",
        )
        waitUntilAddressFilled(
            extraMessage = "MDN の実ページで住所を選んでも入力されない 苗字欄クリック=$fieldClick",
        )
        selectEmailAndWaitUntilFilled(
            extraMessage = "MDN の実ページでメールを個別に入力できない 苗字欄クリック=$fieldClick",
        )
    }

    @Test
    fun focusingMdnAutocompleteMarkupShowsAddressSelectDialog() {
        seedUserReportedAddressWithoutCountry()

        val server = LocalHttpServer(
            pages = mapOf(
                "/$MDN_AUTOCOMPLETE_SAMPLE_FILE_NAME" to buildMdnAutocompleteSampleHtml(),
            ),
        )
        httpServer = server
        val pageUri = "http://127.0.0.1:${server.port}/$MDN_AUTOCOMPLETE_SAMPLE_FILE_NAME"

        selfCheckHttp(pageUri)

        applyTestPrefsAndAwaitAddressAutofillEnabled()
        saveAndSetPref("geckoview.autocomplete.selection_dismiss_delay_ms", 60_000)

        selectFirstAutofillSuggestionOnLocalPage(
            pageUri = pageUri,
            urlMarker = MDN_AUTOCOMPLETE_SAMPLE_FILE_NAME,
            extraMessage = "MDN autocomplete と同じマークアップで住所の候補バーが出ない\n" +
                "サーバ受信リクエスト=${server.requests}",
            afterOpen = { clickMdnFamilyNameField(timeoutMillis = LOCAL_FIELD_CLICK_WAIT_MILLIS) },
        )
        waitUntilAddressFilled(
            extraMessage = "MDN autocomplete と同じマークアップで住所を選んでも入力されない",
        )
        selectEmailAndWaitUntilFilled(
            extraMessage = "MDN autocomplete と同じマークアップでメールを個別に入力できない",
        )
    }

    @Test
    fun focusingMdnMarkupInSandboxedIframeShowsAddressSelectDialog() {
        seedUserReportedAddressWithoutCountry()

        val contentServer = LocalHttpServer(
            pages = mapOf(
                "/$MDN_AUTOCOMPLETE_SAMPLE_FILE_NAME" to buildMdnAutocompleteSampleHtml(),
            ),
        )
        val parentServer = LocalHttpServer(
            pages = mapOf(
                "/iframe-parent.html" to buildSandboxedIframeParentHtml(contentServer.port),
            ),
        )
        httpServer = parentServer

        try {
            val pageUri = "http://127.0.0.1:${parentServer.port}/iframe-parent.html"
            val contentUri = "http://127.0.0.1:${contentServer.port}/$MDN_AUTOCOMPLETE_SAMPLE_FILE_NAME"
            val parentCheck = selfCheckHttp(pageUri)
            val contentCheck = selfCheckHttp(contentUri)
            if (!parentCheck.startsWith("HTTP") || !contentCheck.startsWith("HTTP")) {
                fail("sandbox iframe 用サーバに接続できない parent=$parentCheck content=$contentCheck")
            }
            val contentRequestsBeforeLoad = contentServer.requests.size

            applyTestPrefsAndAwaitAddressAutofillEnabled()
            saveAndSetPref("geckoview.autocomplete.selection_dismiss_delay_ms", 60_000)
            clearLogcat()

            selectFirstAutofillSuggestionOnLocalPage(
                pageUri = pageUri,
                urlMarker = "iframe-parent.html",
                extraMessage = "sandbox iframe 内の MDN マークアップで住所の候補バーが出ない\n" +
                    "親サーバ=${parentServer.requests} 子サーバ=${contentServer.requests}",
                afterOpen = {
                    val iframeLoaded = runCatching {
                        composeRule.waitUntil(timeoutMillis = 20_000) {
                            contentServer.requests.size > contentRequestsBeforeLoad
                        }
                        true
                    }.getOrDefault(false)
                    if (iframeLoaded) {
                        clickMdnFamilyNameField(timeoutMillis = LOCAL_FIELD_CLICK_WAIT_MILLIS)
                    }
                },
            )
            waitUntilAddressFilled(
                extraMessage = "sandbox iframe 内で住所を選んでも入力されない " +
                    "親サーバ=${parentServer.requests} 子サーバ=${contentServer.requests}",
            )
            selectEmailAndWaitUntilFilled(
                extraMessage = "sandbox iframe 内でメールを個別に入力できない " +
                    "親サーバ=${parentServer.requests} 子サーバ=${contentServer.requests}",
            )
        } finally {
            contentServer.close()
        }
    }

    @Test
    fun focusingMozillaAddressFormShowsAddressSelectDialog() {
        seedMozillaSampleAddress()

        val server = LocalHttpServer(
            pages = mapOf(
                "/$ADDRESS_SELECT_FORM_FILE_NAME" to buildMozillaAddressFormHtml(),
            ),
        )
        httpServer = server
        val pageUri = "http://127.0.0.1:${server.port}/$ADDRESS_SELECT_FORM_FILE_NAME"

        selfCheckHttp(pageUri)

        applyTestPrefsAndAwaitAddressAutofillEnabled()
        saveAndSetPref("geckoview.autocomplete.selection_dismiss_delay_ms", 60_000)

        selectFirstAutofillSuggestionOnLocalPage(
            pageUri = pageUri,
            urlMarker = ADDRESS_SELECT_FORM_FILE_NAME,
            extraMessage = "Mozilla の address_form.html で住所の候補バーが出ない\n" +
                "サーバ受信リクエスト=${server.requests}",
        )
        waitUntilAddressFilled(
            extraMessage = "Mozilla の address_form.html で住所を選んでも入力されない",
        )
        selectEmailAndWaitUntilFilled(
            extraMessage = "Mozilla の address_form.html でメールを個別に入力できない",
        )
    }

    @Test
    fun blurringAddressFieldHidesSuggestionBar() {
        seedUserReportedAddressWithoutCountry()

        val server = LocalHttpServer(
            pages = mapOf(
                "/$BLUR_SUGGEST_FILE_NAME" to buildBlurSuggestHtml(),
            ),
        )
        httpServer = server
        val pageUri = "http://127.0.0.1:${server.port}/$BLUR_SUGGEST_FILE_NAME"

        selfCheckHttp(pageUri)

        applyTestPrefsAndAwaitAddressAutofillEnabled()
        saveAndSetPref("geckoview.autocomplete.selection_dismiss_delay_ms", 60_000)

        waitForAutofillSuggestionBarOnLocalPage(
            pageUri = pageUri,
            urlMarker = BLUR_SUGGEST_FILE_NAME,
            extraMessage = "フォーカス時に候補バーが出ない\nサーバ受信リクエスト=${server.requests}",
            afterOpen = { clickFirstContentEditText() },
        )
        val blurred = clickBlurFieldButton()
        try {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule
                    .onAllNodesWithTag(AddressAutofillSuggestionBarTestTags.Bar.testTag)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "フォーカスを外しても候補バーが消えない\n" +
                    "blur=$blurred\n" +
                    "現在URL=${composeRule.currentPageUrlFromUi()}\n" +
                    "--- accessibility ---\n${dumpAccessibilityTree()}\n" +
                    "--- logcat (formautofill関連) ---\n${collectFormAutofillLogcat()}",
                e,
            )
        }
    }

    @Test
    fun submittingAddressFormShowsAddressSaveDialog() {
        val server = LocalHttpServer(
            pages = mapOf(
                "/$ADDRESS_FORM_FILE_NAME" to buildAddressFormHtml(),
                "/$ADDRESS_FORM_DONE_FILE_NAME" to buildDoneHtml(),
            ),
        )
        httpServer = server
        val pageUri = "http://127.0.0.1:${server.port}/$ADDRESS_FORM_FILE_NAME"

        // サーバがテストプロセスから到達可能であることを先に確認する。
        // ここで失敗する場合は Gecko 以前に環境の問題。
        val selfCheck = selfCheckHttp(pageUri)

        applyTestPrefsAndAwaitAddressAutofillEnabled()

        // ページは load 後にフォームへ値を投入して自動送信する (load+8秒, フォールバック+12秒)。
        // 送信先は hidden iframe (メインページは遷移しない) のため、
        // サーバが done.html リクエストを受信したことで送信完了を判定する。
        // 起動時ホームページの遅延ロードがテストページを上書きしたら開き直す。
        val submitted = openLocalPageUntilRequest(
            pageUri = pageUri,
            urlMarker = ADDRESS_FORM_FILE_NAME,
            timeoutMillis = 90_000,
            requestMatched = { server.requests.any { it.contains(ADDRESS_FORM_DONE_FILE_NAME) } },
        )

        clearLogcat()

        // 送信を Gecko の formautofill が検出すると onAddressSave プロンプトが発火し、
        // AddressSaveDialog が表示されるはず。
        waitForAddressSaveDialog(submitted, selfCheck, server)

        composeRule
            .onNodeWithTag(BrowserTabDialogLayerTestTags.AddressSaveConfirmButton.testTag)
            .performClick()

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule
                .onAllNodesWithTag(BrowserTabDialogLayerTestTags.AddressSaveDialog.testTag)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    /**
     * ローカルページで名前候補を選び、ホームページ遅延ロードに上書きされたら開き直す。
     *
     * CI では起動時タブの google.com 読み込みが遅れてコミットされ、
     * URL バーから開いたループバックページを上書きする。
     */
    private fun selectFirstAutofillSuggestionOnLocalPage(
        pageUri: String,
        urlMarker: String,
        extraMessage: String,
        afterOpen: () -> Unit = {},
    ) {
        waitForAutofillSuggestionBarOnLocalPage(
            pageUri = pageUri,
            urlMarker = urlMarker,
            extraMessage = extraMessage,
            afterOpen = afterOpen,
        )
        composeRule
            .onAllNodesWithTag(AddressAutofillSuggestionBarTestTags.NameOption.testTag)
            .onFirst()
            .performClick()
    }

    /**
     * ローカルページで名前候補バーが出るまで待つ。ホームページ遅延ロードに上書きされたら開き直す。
     */
    private fun waitForAutofillSuggestionBarOnLocalPage(
        pageUri: String,
        urlMarker: String,
        extraMessage: String,
        afterOpen: () -> Unit = {},
    ) {
        val deadline = System.currentTimeMillis() + LOCAL_PAGE_RETRY_TIMEOUT_MILLIS
        var opened = false
        while (System.currentTimeMillis() < deadline) {
            val onExpectedPage = isExpectedLocalPage(composeRule.currentPageUrlFromUi(), urlMarker)
            val appeared = composeRule
                .onAllNodesWithTag(AddressAutofillSuggestionBarTestTags.NameOption.testTag)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (appeared && onExpectedPage) {
                return
            }
            if (!opened || !onExpectedPage) {
                openLocalPage(pageUri, urlMarker)
                opened = true
                runCatching { afterOpen() }
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError(
            "$extraMessage\n" +
                "現在URL=${composeRule.currentPageUrlFromUi()}\n" +
                "--- accessibility ---\n${dumpAccessibilityTree()}\n" +
                "--- logcat (formautofill関連) ---\n${collectFormAutofillLogcat()}",
        )
    }

    private fun selectFirstAutofillSuggestion(extraMessage: String) {
        selectAutofillSuggestion(
            optionTestTag = AddressAutofillSuggestionBarTestTags.NameOption.testTag,
            extraMessage = extraMessage,
        )
    }

    private fun selectEmailAutofillSuggestion(extraMessage: String) {
        selectAutofillSuggestion(
            optionTestTag = AddressAutofillSuggestionBarTestTags.EmailOption.testTag,
            extraMessage = extraMessage,
        )
    }

    private fun selectAutofillSuggestion(optionTestTag: String, extraMessage: String) {
        try {
            composeRule.waitUntil(timeoutMillis = 30_000) {
                composeRule
                    .onAllNodesWithTag(optionTestTag)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "$extraMessage\n" +
                    "現在URL=${composeRule.currentPageUrlFromUi()}\n" +
                    "--- accessibility ---\n${dumpAccessibilityTree()}\n" +
                    "--- logcat (formautofill関連) ---\n${collectFormAutofillLogcat()}",
                e,
            )
        }
        composeRule
            .onAllNodesWithTag(optionTestTag)
            .onFirst()
            .performClick()
    }

    /**
     * URL バーからローカルページを開き、フォーカスが外れるまで待つ。
     */
    private fun openLocalPage(pageUri: String, urlMarker: String) {
        composeRule.openUrlFromUrlBar(pageUri)
        runCatching {
            composeRule.waitForUrlBarContains(urlMarker, timeoutMillis = 20_000)
            composeRule.waitForUrlBarNotFocused(timeoutMillis = 15_000)
        }
    }

    /**
     * ローカルページを開き、ホームページに上書きされたら開き直して条件成立を待つ。
     */
    private fun openLocalPageUntilRequest(
        pageUri: String,
        urlMarker: String,
        timeoutMillis: Long,
        requestMatched: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var opened = false
        while (System.currentTimeMillis() < deadline) {
            if (requestMatched()) return true
            val onExpectedPage = isExpectedLocalPage(composeRule.currentPageUrlFromUi(), urlMarker)
            if (!opened || !onExpectedPage) {
                openLocalPage(pageUri, urlMarker)
                opened = true
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return requestMatched()
    }

    private fun isExpectedLocalPage(url: String, urlMarker: String): Boolean {
        if (url.contains("google.com", ignoreCase = true)) return false
        return url.contains(urlMarker)
    }

    private fun waitUntilAddressFilled(extraMessage: String) {
        try {
            composeRule.waitUntil(timeoutMillis = 30_000) {
                pageContainsFilledAddress(FILL_FAMILY_NAME, FILL_GIVEN_NAME) &&
                    !pageContainsFilledEmail()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "$extraMessage\n" +
                    "現在URL=${composeRule.currentPageUrlFromUi()}\n" +
                    "editables=${collectEditableFieldTexts()}\n" +
                    "texts=${collectAccessibilityTexts().take(80)}\n" +
                    "--- accessibility ---\n${dumpAccessibilityTree()}\n" +
                    "--- logcat (formautofill関連) ---\n${collectFormAutofillLogcat()}",
                e,
            )
        }
    }

    private fun selectEmailAndWaitUntilFilled(extraMessage: String) {
        val fieldClick = clickMdnEmailField()
        selectEmailAutofillSuggestion(
            extraMessage = "$extraMessage (メールの候補バーが出ない)\nメール欄クリック=$fieldClick",
        )
        try {
            composeRule.waitUntil(timeoutMillis = 30_000) {
                pageContainsFilledEmail() &&
                    pageContainsFilledAddress(FILL_FAMILY_NAME, FILL_GIVEN_NAME)
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "$extraMessage (メールが入力されない)\n" +
                    "メール欄クリック=$fieldClick\n" +
                    "現在URL=${composeRule.currentPageUrlFromUi()}\n" +
                    "editables=${collectEditableFieldTexts()}\n" +
                    "texts=${collectAccessibilityTexts().take(80)}\n" +
                    "--- accessibility ---\n${dumpAccessibilityTree()}\n" +
                    "--- logcat (formautofill関連) ---\n${collectFormAutofillLogcat()}",
                e,
            )
        }
    }

    private fun pageContainsFilledAddress(familyName: String, givenName: String): Boolean {
        val texts = collectAccessibilityTexts()
        if (texts.any { it.contains("lastName=$familyName") && it.contains("firstName=$givenName") }) {
            return true
        }
        if (texts.any { it.contains("familyName=$familyName") && it.contains("givenName=$givenName") }) {
            return true
        }
        val editables = collectEditableFieldTexts()
        return editables.any { it.contains(familyName) } && editables.any { it.contains(givenName) }
    }

    private fun pageContainsFilledEmail(): Boolean {
        val texts = collectAccessibilityTexts()
        if (texts.any { it.contains("email=$FILL_EMAIL") }) return true
        return collectEditableFieldTexts().any { it.contains(FILL_EMAIL) }
    }

    private fun collectAccessibilityTexts(): List<String> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return emptyList()
        val texts = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.isNotEmpty()) texts.add(text)
            if (desc.isNotEmpty()) texts.add(desc)
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                walk(child)
                child.recycle()
            }
        }
        try {
            walk(root)
        } finally {
            root.recycle()
        }
        return texts
    }

    private fun collectEditableFieldTexts(): List<String> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return emptyList()
        val texts = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo) {
            val viewId = node.viewIdResourceName.orEmpty()
            val cls = node.className?.toString().orEmpty()
            val isChrome = viewId.contains("UrlTextInput", ignoreCase = true) ||
                viewId.contains("toolbar", ignoreCase = true) ||
                node.contentDescription?.toString().orEmpty().contains("Address bar")
            val isEdit = node.isEditable || cls.contains("EditText", ignoreCase = true)
            if (isEdit && !isChrome) {
                texts.add(node.text?.toString().orEmpty())
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                walk(child)
                child.recycle()
            }
        }
        try {
            walk(root)
        } finally {
            root.recycle()
        }
        return texts
    }

    private fun waitForAddressSaveDialog(
        submitted: Boolean,
        selfCheck: String,
        server: LocalHttpServer,
    ) {
        try {
            composeRule.waitUntil(timeoutMillis = 60_000) {
                composeRule
                    .onAllNodesWithTag(BrowserTabDialogLayerTestTags.AddressSaveDialog.testTag)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            val pageLoadErrorVisible = composeRule
                .onAllNodesWithTag(BrowserTabSurfaceTestTags.PageLoadError.testTag)
                .fetchSemanticsNodes()
                .isNotEmpty()
            throw AssertionError(
                "住所保存ダイアログが表示されない (フォーム送信=$submitted)\n" +
                    "現在URL=${composeRule.currentPageUrlFromUi()}\n" +
                    "PageLoadError表示=$pageLoadErrorVisible 内容=${pageLoadErrorText()}\n" +
                    "サーバ受信リクエスト=${server.requests}\n" +
                    "テストプロセスからの自己接続=$selfCheck\n" +
                    "関連プレフ=${dumpNetworkPrefs()}\n" +
                    "--- logcat (formautofill関連) ---\n${collectFormAutofillLogcat()}",
                e,
            )
        }
    }

    private data class SavedPref(
        val name: String,
        val value: Any?,
        val hadValue: Boolean,
    )

    @OptIn(ExperimentalGeckoViewApi::class)
    private fun saveAndSetPref(name: String, value: Any) {
        val current = awaitGeckoResult {
            GeckoPreferenceController.getGeckoPrefs(listOf(name))
        }?.firstOrNull()
        savedPrefs.add(SavedPref(name, current?.value, current != null))
        when (value) {
            is Boolean -> awaitGeckoResult {
                GeckoPreferenceController.setGeckoPref(
                    name,
                    value,
                    GeckoPreferenceController.PREF_BRANCH_USER,
                )
            }
            is String -> awaitGeckoResult {
                GeckoPreferenceController.setGeckoPref(
                    name,
                    value,
                    GeckoPreferenceController.PREF_BRANCH_USER,
                )
            }
            is Int -> awaitGeckoResult {
                GeckoPreferenceController.setGeckoPref(
                    name,
                    value,
                    GeckoPreferenceController.PREF_BRANCH_USER,
                )
            }
        }
    }

    @OptIn(ExperimentalGeckoViewApi::class)
    private fun restoreSavedPrefs() {
        savedPrefs.forEach { saved ->
            val value = if (saved.hadValue) saved.value else defaultPrefValue(saved.name)
            when (value) {
                is Boolean -> awaitGeckoResult {
                    GeckoPreferenceController.setGeckoPref(
                        saved.name,
                        value,
                        GeckoPreferenceController.PREF_BRANCH_USER,
                    )
                }
                is String -> awaitGeckoResult {
                    GeckoPreferenceController.setGeckoPref(
                        saved.name,
                        value,
                        GeckoPreferenceController.PREF_BRANCH_USER,
                    )
                }
                is Int -> awaitGeckoResult {
                    GeckoPreferenceController.setGeckoPref(
                        saved.name,
                        value,
                        GeckoPreferenceController.PREF_BRANCH_USER,
                    )
                }
            }
        }
        savedPrefs.clear()
    }

    private fun defaultPrefValue(name: String): Any? = when (name) {
        "extensions.formautofill.skipProgrammaticCheckForTests" -> false
        "extensions.formautofill.loglevel" -> "Warn"
        "geckoview.console.enabled" -> false
        "devtools.console.stdout.content" -> false
        "network.lna.enabled" -> true
        "network.lna.blocking" -> true
        "geckoview.autocomplete.selection_dismiss_delay_ms" -> 0
        "extensions.formautofill.addresses.supported" -> "detect"
        else -> null
    }

    private fun seedUserReportedAddressWithoutCountry() {
        val repository = AddressRepository(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        runBlocking {
            repository.deleteAll()
            repository.save(
                AddressEntity(
                    givenName = FILL_GIVEN_NAME,
                    familyName = FILL_FAMILY_NAME,
                    addressLevel1 = "c",
                    addressLevel2 = "i",
                    addressLevel3 = "2",
                    streetAddress = "p",
                    postalCode = "2222222",
                    tel = "09011111111",
                    email = FILL_EMAIL,
                ),
            )
        }
        seededAddresses = true
    }

    private fun seedMozillaSampleAddress() {
        val repository = AddressRepository(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        runBlocking {
            repository.deleteAll()
            repository.save(
                AddressEntity(
                    givenName = FILL_GIVEN_NAME,
                    familyName = FILL_FAMILY_NAME,
                    streetAddress = "20 Ingram Street, Forest Hills Gardens, Queens",
                    postalCode = "11375",
                    country = "US",
                    email = FILL_EMAIL,
                    tel = "+1 180090021",
                ),
            )
        }
        seededAddresses = true
    }

    private fun clearSeededAddresses() {
        val repository = AddressRepository(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        runBlocking {
            repository.deleteAll()
        }
    }

    /**
     * GeckoView 本家 AutocompleteTest が使う address_form.html そのもの。
     * https://github.com/mozilla-firefox/firefox/blob/main/mobile/android/geckoview/src/androidTest/assets/www/address_form.html
     *
     * 本家テストは evaluateJS で #givenName にフォーカスする。ここではページ内 JS で同じ操作をする。
     */
    private fun buildMozillaAddressFormHtml(): String {
        return """
            <html>
              <head>
                <meta charset="utf-8" />
                <title>Address form</title>
              </head>
              <body>
                <form>
                  <input autocomplete="name" id="name" />
                  <input autocomplete="given-name" id="givenName" />
                  <input autocomplete="additional-name" id="additionalName" />
                  <input autocomplete="family-name" id="familyName" />
                  <input autocomplete="street-address" id="streetAddress" />
                  <input autocomplete="country" id="country" />
                  <input autocomplete="postal-code" id="postalCode" />
                  <input autocomplete="organization" id="organization" />
                  <input autocomplete="email" id="email" />
                  <input autocomplete="tel" id="tel" />
                  <input type="submit" value="Submit" />
                </form>
                <pre id="fill-probe"></pre>
                <script>
                  function reportFill() {
                    const probe = document.getElementById('fill-probe');
                    if (!probe) return;
                    const given = document.getElementById('givenName');
                    const family = document.getElementById('familyName');
                    const email = document.getElementById('email');
                    probe.textContent =
                      'givenName=' + (given ? given.value : '') +
                      ' familyName=' + (family ? family.value : '') +
                      ' email=' + (email ? email.value : '');
                  }
                  window.addEventListener('load', () => {
                    setInterval(reportFill, 250);
                    setTimeout(() => {
                      document.getElementById('givenName').focus();
                    }, 2000);
                  });
                </script>
              </body>
            </html>
        """.trimIndent()
    }

    /**
     * MDN の autocomplete ドキュメント「試してみましょう」と同じマークアップ。
     * form 要素はない。苗字欄へフォーカスして選択ダイアログを誘発する。
     */
    private fun buildMdnAutocompleteSampleHtml(): String {
        return """
            <!doctype html>
            <html lang="ja">
              <head>
                <meta charset="utf-8" />
                <title>HTML デモ: autocomplete</title>
              </head>
              <body>
                <label for="lastName">苗字:</label>
                <input name="lastName" id="lastName" type="text" autocomplete="family-name" />

                <label for="firstName">名前:</label>
                <input name="firstName" id="firstName" type="text" autocomplete="given-name" />

                <label for="email">メールアドレス:</label>
                <input name="email" id="email" type="email" autocomplete="off" />
                <pre id="fill-probe"></pre>
                <script>
                  function reportFill() {
                    const probe = document.getElementById('fill-probe');
                    if (!probe) return;
                    const lastName = document.getElementById('lastName');
                    const firstName = document.getElementById('firstName');
                    const email = document.getElementById('email');
                    probe.textContent =
                      'lastName=' + (lastName ? lastName.value : '') +
                      ' firstName=' + (firstName ? firstName.value : '') +
                      ' email=' + (email ? email.value : '');
                  }
                  window.addEventListener('load', () => {
                    setInterval(reportFill, 250);
                    setTimeout(() => {
                      document.getElementById('lastName').focus();
                    }, 2000);
                  });
                </script>
              </body>
            </html>
        """.trimIndent()
    }

    private fun buildSandboxedIframeParentHtml(contentPort: Int): String {
        val iframeSrc = "http://127.0.0.1:$contentPort/$MDN_AUTOCOMPLETE_SAMPLE_FILE_NAME"
        return """
            <!doctype html>
            <html lang="ja">
              <head>
                <meta charset="utf-8" />
                <title>MDN sample iframe</title>
              </head>
              <body>
                <p>parent</p>
                <iframe
                  sandbox="allow-scripts allow-same-origin allow-forms"
                  src="$iframeSrc"
                  style="width:100%;height:400px;border:1px solid #000"
                ></iframe>
              </body>
            </html>
        """.trimIndent()
    }

    private fun clickFirstContentEditText(
        timeoutMillis: Long = LOCAL_FIELD_CLICK_WAIT_MILLIS,
    ): String {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val root = uiAutomation.rootInActiveWindow
            if (root != null) {
                val target = findFirstEmptyContentEditText(root)
                if (target != null) {
                    val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val summary = "clicked=$clicked cls=${target.className}"
                    target.recycle()
                    root.recycle()
                    return summary
                }
                root.recycle()
            }
            Thread.sleep(500)
        }
        return "not-found"
    }

    private fun clickBlurFieldButton(
        timeoutMillis: Long = LOCAL_FIELD_CLICK_WAIT_MILLIS,
    ): String {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val root = uiAutomation.rootInActiveWindow
            if (root != null) {
                val clicked = clickNodeWithText(root, setOf(BLUR_FIELD_BUTTON_LABEL))
                root.recycle()
                if (clicked) return "clicked"
            }
            Thread.sleep(500)
        }
        return "not-found"
    }

    /**
     * MDN ライブサンプルの苗字欄をクリックする。
     *
     * 実ページの入力は cross-origin iframe 内にあるため、ページ内 JS ではフォーカスできない。
     * 生の MotionEvent は使わず、AccessibilityNodeInfo の ACTION_CLICK / ACTION_FOCUS で
     * ユーザーが欄をタップしたのと同じフォーカスを入れる。
     *
     * HTML タブのコードエディタも「苗字」を含むので、ソース編集欄は除外する。
     */
    private fun clickMdnFamilyNameField(
        timeoutMillis: Long = MDN_FIELD_WAIT_MILLIS,
    ): String {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastDump = ""
        var clickedOutputTab = false
        while (System.currentTimeMillis() < deadline) {
            val root = uiAutomation.rootInActiveWindow
            if (root != null) {
                if (!clickedOutputTab) {
                    clickedOutputTab = clickNodeWithText(root, OUTPUT_TAB_LABELS)
                }
                val dump = StringBuilder()
                val target = findMdnFamilyNameField(root, dump)
                    ?: if (clickedOutputTab) findFirstEmptyContentEditText(root) else null
                lastDump = dump.toString()
                if (target != null) {
                    val viewId = target.viewIdResourceName.orEmpty()
                    val desc = target.contentDescription?.toString().orEmpty()
                    val text = target.text?.toString().orEmpty()
                    val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val summary =
                        "clicked=$clicked cls=${target.className} viewId=$viewId desc=$desc " +
                            "text=${text.take(80)} edit=${target.isEditable} outputTab=$clickedOutputTab"
                    target.recycle()
                    root.recycle()
                    return summary
                }
                scrollAccessibilityNode(root)
                root.recycle()
            }
            Thread.sleep(500)
        }
        return "not-found outputTab=$clickedOutputTab dump=\n$lastDump"
    }

    private fun clickMdnEmailField(): String {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.currentTimeMillis() + MDN_FIELD_WAIT_MILLIS
        var lastDump = ""
        var clickedOutputTab = false
        while (System.currentTimeMillis() < deadline) {
            val root = uiAutomation.rootInActiveWindow
            if (root != null) {
                if (!clickedOutputTab) {
                    clickedOutputTab = clickNodeWithText(root, OUTPUT_TAB_LABELS)
                }
                val dump = StringBuilder()
                val target = findMdnEmailField(root, dump)
                lastDump = dump.toString()
                if (target != null) {
                    val viewId = target.viewIdResourceName.orEmpty()
                    val desc = target.contentDescription?.toString().orEmpty()
                    val text = target.text?.toString().orEmpty()
                    val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val summary =
                        "clicked=$clicked cls=${target.className} viewId=$viewId desc=$desc " +
                            "text=${text.take(80)} edit=${target.isEditable} outputTab=$clickedOutputTab"
                    target.recycle()
                    root.recycle()
                    return summary
                }
                scrollAccessibilityNode(root)
                root.recycle()
            }
            Thread.sleep(500)
        }
        return "not-found outputTab=$clickedOutputTab dump=\n$lastDump"
    }

    private fun clickNodeWithText(root: AccessibilityNodeInfo, labels: Set<String>): Boolean {
        val node = findNode(root) { candidate ->
            val text = candidate.text?.toString().orEmpty()
            val desc = candidate.contentDescription?.toString().orEmpty()
            labels.any { label -> text == label || desc == label }
        } ?: return false
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.recycle()
        return clicked
    }

    private fun findMdnFamilyNameField(
        node: AccessibilityNodeInfo,
        dump: StringBuilder,
        depth: Int = 0,
    ): AccessibilityNodeInfo? {
        val viewId = node.viewIdResourceName.orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val text = node.text?.toString().orEmpty()
        val cls = node.className?.toString().orEmpty()
        if (dump.lines().size < ACCESSIBILITY_DUMP_MAX_LINES) {
            val interesting = node.isEditable ||
                cls.contains("EditText", ignoreCase = true) ||
                viewId.contains("lastName", ignoreCase = true) ||
                text.contains("苗字") ||
                desc.contains("苗字") ||
                text in OUTPUT_TAB_LABELS ||
                desc in OUTPUT_TAB_LABELS
            if (interesting) {
                dump.append("  ".repeat(depth.coerceAtMost(16)))
                    .append(cls.substringAfterLast('.'))
                    .append(" id=").append(viewId)
                    .append(" text=").append(text.take(40))
                    .append(" desc=").append(desc.take(40))
                    .append(" edit=").append(node.isEditable)
                    .append('\n')
            }
        }
        if (isMdnLiveFamilyNameField(node)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findMdnFamilyNameField(child, dump, depth + 1)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun isMdnLiveFamilyNameField(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName.orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val text = node.text?.toString().orEmpty()
        val cls = node.className?.toString().orEmpty()
        // CodeMirror の行は isEditable な View なので、HTML の input に相当する EditText だけを対象にする
        if (!cls.contains("EditText", ignoreCase = true)) return false
        if (isSourceEditorText(text) || isSourceEditorText(desc)) return false
        return viewId.endsWith("lastName", ignoreCase = true) ||
            desc.contains("苗字") ||
            desc.contains("family-name", ignoreCase = true) ||
            text.isBlank()
    }

    private fun findMdnEmailField(
        node: AccessibilityNodeInfo,
        dump: StringBuilder,
        depth: Int = 0,
    ): AccessibilityNodeInfo? {
        val viewId = node.viewIdResourceName.orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val text = node.text?.toString().orEmpty()
        val cls = node.className?.toString().orEmpty()
        if (dump.lines().size < ACCESSIBILITY_DUMP_MAX_LINES) {
            val interesting = node.isEditable ||
                cls.contains("EditText", ignoreCase = true) ||
                viewId.contains("email", ignoreCase = true) ||
                text.contains("メール") ||
                desc.contains("メール") ||
                text.contains("email", ignoreCase = true) ||
                desc.contains("email", ignoreCase = true)
            if (interesting) {
                dump.append("  ".repeat(depth.coerceAtMost(16)))
                    .append(cls.substringAfterLast('.'))
                    .append(" id=").append(viewId)
                    .append(" text=").append(text.take(40))
                    .append(" desc=").append(desc.take(40))
                    .append(" edit=").append(node.isEditable)
                    .append('\n')
            }
        }
        if (isMdnLiveEmailField(node)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findMdnEmailField(child, dump, depth + 1)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun isMdnLiveEmailField(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName.orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val text = node.text?.toString().orEmpty()
        val hint = node.hintText?.toString().orEmpty()
        val cls = node.className?.toString().orEmpty()
        if (!cls.contains("EditText", ignoreCase = true)) return false
        if (isSourceEditorText(text) || isSourceEditorText(desc) || isSourceEditorText(hint)) return false
        return viewId.contains("email", ignoreCase = true) ||
            desc.contains("メール") ||
            desc.contains("e-mail", ignoreCase = true) ||
            desc.contains("email", ignoreCase = true) ||
            hint.contains("メール") ||
            hint.contains("e-mail", ignoreCase = true) ||
            hint.contains("email", ignoreCase = true)
    }

    private fun isSourceEditorText(value: String): Boolean {
        return value.contains("<label") ||
            value.contains("<input") ||
            value.contains("autocomplete=")
    }

    private fun findFirstEmptyContentEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val viewId = node.viewIdResourceName.orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val text = node.text?.toString().orEmpty()
        val cls = node.className?.toString().orEmpty()
        val isEdit = node.isEditable || cls.contains("EditText", ignoreCase = true)
        val isChrome = viewId.contains("UrlTextInput", ignoreCase = true) ||
            desc.contains("Address bar") ||
            viewId.contains("toolbar", ignoreCase = true)
        if (isEdit && !isChrome && text.isBlank() && !isSourceEditorText(text) && !isSourceEditorText(desc)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findFirstEmptyContentEditText(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findNode(child, predicate)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun scrollAccessibilityNode(node: AccessibilityNodeInfo) {
        if (node.isScrollable) {
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            scrollAccessibilityNode(child)
            child.recycle()
        }
    }

    private fun dumpAccessibilityTree(): String {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return "(rootInActiveWindow=null)"
        val dump = StringBuilder()
        findMdnFamilyNameField(root, dump)
        findMdnEmailField(root, dump)
        root.recycle()
        return dump.toString()
    }

    /**
     * テストプロセスからサーバへ HTTP 接続できるか確認する。失敗時の診断用。
     *
     * Android の cleartext 制限は HttpURLConnection にだけ掛かるため、
     * 生ソケットで送受信する。Gecko のページ読み込み可否とは別経路。
     */
    private fun selfCheckHttp(url: String): String {
        return runCatching {
            val parsed = URL(url)
            val port = parsed.port.takeIf { it > 0 } ?: parsed.defaultPort
            Socket().use { socket ->
                socket.soTimeout = 5_000
                socket.connect(InetSocketAddress(parsed.host, port), 5_000)
                val path = parsed.path.ifEmpty { "/" }
                val request =
                    "GET $path HTTP/1.1\r\nHost: ${parsed.host}:$port\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader().readLine() ?: "empty"
            }
        }.getOrElse { "失敗: $it" }
    }

    /**
     * ネットワーク関連の Gecko プレフを収集する。失敗時の診断用。
     */
    @OptIn(ExperimentalGeckoViewApi::class)
    private fun dumpNetworkPrefs(): String {
        val prefs = awaitGeckoResult {
            GeckoPreferenceController.getGeckoPrefs(
                listOf(
                    "dom.security.https_only_mode",
                    "dom.security.https_first",
                    "network.proxy.type",
                    "network.lna.enabled",
                    "network.lna.blocking",
                ),
            )
        }
        return prefs.orEmpty().joinToString(", ") { "${it.pref}=${it.value}" }
    }

    /**
     * ページロードエラー画面に表示されているテキストを収集する。失敗時の診断用。
     */
    private fun pageLoadErrorText(): String {
        return runCatching {
            val node = composeRule
                .onNodeWithTag(BrowserTabSurfaceTestTags.PageLoadError.testTag, useUnmergedTree = true)
                .fetchSemanticsNode()
            collectTexts(node).joinToString(" / ")
        }.getOrDefault("")
    }

    private fun collectTexts(node: SemanticsNode): List<String> {
        val own = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
        return own + node.children.flatMap { collectTexts(it) }
    }

    /**
     * logcat をクリアする。
     */
    private fun clearLogcat() {
        runCatching {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("logcat -c")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
    }

    /**
     * formautofill の capture 判定に関わる logcat を収集する。失敗時の診断用。
     *
     * console.debug の本文は接頭辞と別行の Gecko タグで出力されるため、
     * capture 判定パスのキーワードを含む本文行を狙って拾う。
     */
    private fun collectFormAutofillLogcat(): String {
        val output = runCatching {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("logcat -d")
            ParcelFileDescriptor.AutoCloseInputStream(pfd)
                .bufferedReader()
                .readLines()
        }.getOrElse { return "logcat取得失敗: $it" }
        val excludes = listOf(
            "TestRunner",
            "MediaBridge",
            "updateActiveElement",
            "Disregarding",
            "ThemeColor",
            "getSavedFieldNames",
            "updateSavedFieldNames",
        )
        val includes = listOf(
            " I Gecko ",
            " E Gecko ",
            " W Gecko ",
            "GeckoConsole",
            "addr-test",
            "GeckoView:Prompt",
            "AutocompleteStorage",
            "PromptDialogState",
            "delegateSelection",
            "onAddressSelect",
            "onAddressFetch",
            "AddressAutofill",
            "AddressAutofillExt",
            "address-autofill",
        )
        // logcat は入力完了後にクリアしているため、先頭側 (送信直後) に重要なログが集まる
        return output
            .filter { line -> excludes.none { line.contains(it) } }
            .filter { line -> includes.any { line.contains(it, ignoreCase = true) } }
            .take(LOGCAT_TAIL_LINES)
            .joinToString("\n")
    }

    /**
     * テスト用プリファレンスを設定し、AppModule が設定する住所自動入力プリファレンスの反映を待つ。
     *
     * skipProgrammaticCheckForTests は GeckoView 本家の AutocompleteTest と同様に、
     * JavaScript によるプログラム的なフォーム操作を formautofill が無視しないようにする。
     */
    @OptIn(ExperimentalGeckoViewApi::class)
    private fun applyTestPrefsAndAwaitAddressAutofillEnabled(
        forceAddressSupportedOn: Boolean = true,
    ) {
        saveAndSetPref("extensions.formautofill.skipProgrammaticCheckForTests", true)
        // 失敗時の診断のため formautofill の Debug ログを logcat (GeckoConsole) に出す
        saveAndSetPref("extensions.formautofill.loglevel", "Debug")
        // テストページの console.log を logcat に出してページ内 JS の進行を確認できるようにする
        saveAndSetPref("geckoview.console.enabled", true)
        saveAndSetPref("devtools.console.stdout.content", true)
        // CI の診断でループバック HTTP サーバへの接続が一切行われずページロードに失敗して
        // いたため、Local Network Access のブロッキングを無効化する
        saveAndSetPref("network.lna.enabled", false)
        saveAndSetPref("network.lna.blocking", false)

        if (forceAddressSupportedOn) {
            saveAndSetPref("extensions.formautofill.addresses.supported", "on")
        }

        val deadline = System.currentTimeMillis() + PREF_TIMEOUT_MILLIS
        while (true) {
            val prefs = awaitGeckoResult {
                GeckoPreferenceController.getGeckoPrefs(
                    listOf(
                        "extensions.formautofill.addresses.enabled",
                        "extensions.formautofill.addresses.capture.enabled",
                        "extensions.formautofill.addresses.supported",
                    ),
                )
            }
            val values = prefs.orEmpty().associate { it.pref to it.value }
            val enabled = values["extensions.formautofill.addresses.enabled"] == true &&
                values["extensions.formautofill.addresses.capture.enabled"] == true
            val supportedOk = !forceAddressSupportedOn ||
                values["extensions.formautofill.addresses.supported"] == "on"
            if (enabled && supportedOk) return
            if (System.currentTimeMillis() > deadline) {
                fail("住所自動入力プリファレンスが適用されていない: $values")
            }
            Thread.sleep(500)
        }
    }

    /**
     * GeckoResult をメインスレッドで生成し、テストスレッドで完了を待つ。
     *
     * GeckoResult の生成 (内部の then/map 連鎖) は Handler を持つスレッドで行う必要があり、
     * poll はメインスレッドでは呼べないため、生成と待機でスレッドを分ける。
     * poll のタイムアウトは呼び出し側のリトライと診断メッセージに委ねるため null を返す。
     */
    private fun <T> awaitGeckoResult(block: () -> GeckoResult<T>): T? {
        var geckoResult: GeckoResult<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            geckoResult = block()
        }
        return try {
            requireNotNull(geckoResult).poll(PREF_POLL_TIMEOUT_MILLIS)
        } catch (@Suppress("SwallowedException") e: TimeoutException) {
            null
        }
    }

    /**
     * 名前欄フォーカス後に空領域相当の blur を起こすテストページ。
     * ボタンは mousedown で preventDefault し、自身へフォーカスを移さずに input.blur() する。
     */
    private fun buildBlurSuggestHtml(): String {
        return """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8" />
                <title>Blur Suggest Test</title>
              </head>
              <body>
                <form>
                  <label for="given-name">Given name</label>
                  <input id="given-name" name="given-name" autocomplete="given-name" autofocus />
                  <button id="blur-field" type="button">$BLUR_FIELD_BUTTON_LABEL</button>
                </form>
                <script>
                  const input = document.getElementById('given-name');
                  const button = document.getElementById('blur-field');
                  function blurWithoutTakingFocus() {
                    input.blur();
                  }
                  button.addEventListener('mousedown', function (event) {
                    event.preventDefault();
                    blurWithoutTakingFocus();
                  });
                  button.addEventListener('click', function () {
                    blurWithoutTakingFocus();
                  });
                  input.focus();
                </script>
              </body>
            </html>
        """.trimIndent()
    }

    /**
     * 住所フォームのテストページ。
     *
     * formautofill の対象判定はロケール・地域に依存するため、確実に対象となる US 形式の住所を使う。
     */
    private fun buildAddressFormHtml(): String {
        return """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8" />
                <title>Address Form Test</title>
              </head>
              <body>
                <!-- メインページを遷移させると doorhanger 表示前にドキュメントが破棄されて
                     プロンプトが出ないため、hidden iframe へ送信する -->
                <form id="address-form" method="get" action="/$ADDRESS_FORM_DONE_FILE_NAME" target="result-frame">
                  <input id="given-name" name="given-name" autocomplete="given-name" />
                  <input id="family-name" name="family-name" autocomplete="family-name" />
                  <input id="organization" name="organization" autocomplete="organization" />
                  <input id="street-address" name="street-address" autocomplete="street-address" />
                  <input id="address-level2" name="city" autocomplete="address-level2" />
                  <input id="address-level1" name="state" autocomplete="address-level1" />
                  <input id="postal-code" name="zip" autocomplete="postal-code" />
                  <select id="country" name="country" autocomplete="country">
                    <option value="US" selected>United States</option>
                  </select>
                  <input id="tel" name="tel" autocomplete="tel" />
                  <input id="email" name="email" autocomplete="email" />
                  <button id="submit-button" type="submit">Submit</button>
                </form>
                <iframe name="result-frame" id="result-frame" style="width:1px;height:1px;border:0"></iframe>
                <script>
                  function log(message) {
                    console.log('addr-test: ' + message);
                  }
                  window.addEventListener('error', (e) => {
                    log('js-error: ' + e.message);
                  });
                  function setValue(id, value) {
                    const el = document.getElementById(id);
                    el.focus();
                    el.value = value;
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                  }
                  // GeckoView の formautofill は focusin がフィールド検出のトリガーで、
                  // 検出とフォーム送信リスナー登録は親プロセスとの非同期通信で行われる。
                  // 同一タスク内で focus→入力→送信まで行うとリスナー登録前に送信されて
                  // capture が動かないため、検出起動・入力・送信を時間差で分ける。
                  window.addEventListener('load', () => {
                    log('load');
                    let submitAttempted = false;
                    let resultLoaded = false;
                    document.getElementById('result-frame').addEventListener('load', () => {
                      if (!submitAttempted) {
                        log('result-frame-ignored before-submit');
                        return;
                      }
                      resultLoaded = true;
                      log('result-frame-loaded');
                    });
                    setTimeout(() => {
                      log('focus');
                      document.getElementById('given-name').focus();
                    }, 2000);
                    setTimeout(() => {
                      log('fill');
                      setValue('given-name', 'John');
                      setValue('family-name', 'Doe');
                      setValue('organization', 'Example Inc');
                      setValue('street-address', '123 Main Street');
                      setValue('address-level2', 'Mountain View');
                      setValue('address-level1', 'CA');
                      setValue('postal-code', '94043');
                      setValue('tel', '+16505551234');
                      setValue('email', 'john.doe@example.com');
                      log('fill-done value=' + document.getElementById('given-name').value);
                    }, 5000);
                    setTimeout(() => {
                      log('submit href=' + location.href);
                      submitAttempted = true;
                      const form = document.getElementById('address-form');
                      if (form.requestSubmit) {
                        form.requestSubmit();
                      } else {
                        form.submit();
                      }
                      log('submit-called');
                    }, 8000);
                    setTimeout(() => {
                      if (resultLoaded) {
                        log('fallback-submit skipped already-loaded');
                        return;
                      }
                      log('fallback-submit still-here href=' + location.href);
                      document.getElementById('address-form').submit();
                    }, 12000);
                  });
                </script>
              </body>
            </html>
        """.trimIndent()
    }

    private fun buildDoneHtml(): String {
        return """
            <!doctype html>
            <html lang="en">
              <head><meta charset="utf-8" /><title>Done</title></head>
              <body><main>Submitted</main></body>
            </html>
        """.trimIndent()
    }

    /**
     * テストページ配信用の最小限のループバック HTTP サーバ。
     *
     * file:// ではフォーム送信が行われないため、http:// でページを配信する。
     */
    private class LocalHttpServer(
        private val pages: Map<String, String>,
    ) : AutoCloseable {
        // 全インターフェースにバインドする (ループバック限定だと Gecko からの接続が
        // 拒否される事象の切り分けのため)
        private val serverSocket = ServerSocket(0, BACKLOG)

        val port: Int get() = serverSocket.localPort

        /** 受信した HTTP リクエストライン。失敗時の診断用。 */
        val requests = CopyOnWriteArrayList<String>()

        init {
            Thread {
                while (!serverSocket.isClosed) {
                    val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                    Thread { handle(socket) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun handle(socket: Socket) {
            runCatching {
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return
                    requests.add(requestLine)
                    // リクエストヘッダは読み捨てる
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val path = requestLine.split(" ").getOrNull(1)
                        ?.substringBefore("?")
                        ?: "/"
                    val body = pages[path]
                    val output = s.getOutputStream()
                    if (body != null) {
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/html; charset=utf-8\r\n" +
                                    "Content-Length: ${bytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(),
                        )
                        output.write(bytes)
                    } else {
                        output.write(
                            (
                                "HTTP/1.1 404 Not Found\r\n" +
                                    "Content-Length: 0\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(),
                        )
                    }
                    output.flush()
                }
            }
        }

        override fun close() {
            runCatching { serverSocket.close() }
        }

        private companion object {
            private const val BACKLOG = 8
        }
    }

    private companion object {
        private const val ADDRESS_FORM_FILE_NAME = "address-form.html"
        private const val ADDRESS_SELECT_FORM_FILE_NAME = "address_form.html"
        private const val ADDRESS_FORM_DONE_FILE_NAME = "done.html"
        private const val MDN_AUTOCOMPLETE_PAGE_URL =
            "https://developer.mozilla.org/ja/docs/Web/HTML/Reference/Attributes/autocomplete"
        private const val MDN_AUTOCOMPLETE_SAMPLE_FILE_NAME = "mdn-autocomplete-sample.html"
        private const val BLUR_SUGGEST_FILE_NAME = "blur-suggest.html"
        private const val BLUR_FIELD_BUTTON_LABEL = "blur-field"
        private const val PREF_TIMEOUT_MILLIS = 30_000L
        private const val PREF_POLL_TIMEOUT_MILLIS = 5_000L
        private const val LOGCAT_TAIL_LINES = 250
        private const val MDN_FIELD_WAIT_MILLIS = 60_000L
        private const val LOCAL_FIELD_CLICK_WAIT_MILLIS = 5_000L
        private const val LOCAL_PAGE_RETRY_TIMEOUT_MILLIS = 90_000L
        private const val POLL_INTERVAL_MILLIS = 250L
        private const val ACCESSIBILITY_DUMP_MAX_LINES = 500
        private const val FILL_FAMILY_NAME = "YamadaFillTest"
        private const val FILL_GIVEN_NAME = "TaroFillTest"
        private const val FILL_EMAIL = "fill-test@example.com"
        private val OUTPUT_TAB_LABELS = setOf("出力", "Output", "結果", "Play")
    }
}
