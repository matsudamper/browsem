package net.matsudamper.browser

import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import java.net.HttpURLConnection
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
 * 保存済み住所がある状態で GeckoView 本家の address_form.html の住所フィールドに
 * フォーカスしたときに選択ダイアログが表示されることを検証する。
 * フォームへの値の投入と送信はページ内 JavaScript で行うため、Web コンテンツへの直接操作は行わない。
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

        composeRule.openUrlFromUrlBar(pageUri)
        composeRule.waitForUrlBarContains(ADDRESS_SELECT_FORM_FILE_NAME, timeoutMillis = 60_000)

        try {
            composeRule.waitUntil(timeoutMillis = 60_000) {
                composeRule
                    .onAllNodesWithTag(BrowserTabDialogLayerTestTags.AddressSelectDialog.testTag)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "Mozilla の address_form.html で住所選択ダイアログが表示されない\n" +
                    "現在URL=${composeRule.currentPageUrlFromUi()}\n" +
                    "サーバ受信リクエスト=${server.requests}\n" +
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

        composeRule.openUrlFromUrlBar(pageUri)
        composeRule.waitForUrlBarContains(ADDRESS_FORM_FILE_NAME, timeoutMillis = 60_000)

        // ページは load 後にフォームへ値を投入して自動送信する (load+8秒, フォールバック+12秒)。
        // 送信先は hidden iframe (メインページは遷移しない) のため、
        // サーバが done.html リクエストを受信したことで送信完了を判定する。
        val submitted = runCatching {
            composeRule.waitUntil(timeoutMillis = 60_000) {
                server.requests.any { it.contains(ADDRESS_FORM_DONE_FILE_NAME) }
            }
        }.isSuccess

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
        else -> null
    }

    private fun seedMozillaSampleAddress() {
        val repository = AddressRepository(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        runBlocking {
            repository.deleteAll()
            repository.save(
                AddressEntity(
                    givenName = "Peter",
                    familyName = "Parker",
                    streetAddress = "20 Ingram Street, Forest Hills Gardens, Queens",
                    postalCode = "11375",
                    country = "US",
                    email = "spiderman@newyork.com",
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
                <script>
                  window.addEventListener('load', () => {
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
     * テストプロセス自身からサーバへ HTTP 接続できるか確認する。失敗時の診断用。
     */
    private fun selfCheckHttp(url: String): String {
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            val code = connection.responseCode
            connection.disconnect()
            "HTTP $code"
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
    private fun applyTestPrefsAndAwaitAddressAutofillEnabled() {
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
            val applied = values["extensions.formautofill.addresses.enabled"] == true &&
                values["extensions.formautofill.addresses.capture.enabled"] == true &&
                values["extensions.formautofill.addresses.supported"] == "on"
            if (applied) return
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
                      const form = document.getElementById('address-form');
                      if (form.requestSubmit) {
                        form.requestSubmit();
                      } else {
                        form.submit();
                      }
                      log('submit-called');
                    }, 8000);
                    setTimeout(() => {
                      // 8 秒時点の送信で遷移しなかった場合のフォールバック
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
        private const val PREF_TIMEOUT_MILLIS = 30_000L
        private const val PREF_POLL_TIMEOUT_MILLIS = 5_000L
        private const val LOGCAT_TAIL_LINES = 250
    }
}
