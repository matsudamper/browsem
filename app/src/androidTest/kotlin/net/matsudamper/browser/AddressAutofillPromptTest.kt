package net.matsudamper.browser

import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.ExperimentalGeckoViewApi
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoResult
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeoutException

/**
 * GeckoView の住所フォーム自動入力 (formautofill) が実際に動作することを確認するテスト。
 *
 * AppModule で有効化した extensions.formautofill.addresses.capture.enabled により、
 * 住所フォーム送信時に PromptDelegate.onAddressSave が発火して保存ダイアログが表示されることを検証する。
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

    @After
    fun tearDown() {
        httpServer?.close()
        httpServer = null
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

        applyTestPrefsAndAwaitAddressAutofillEnabled()

        composeRule.openUrlFromUrlBar(pageUri)
        composeRule.waitForUrlBarContains(ADDRESS_FORM_FILE_NAME, timeoutMillis = 60_000)

        // ページは load 後にフォームへ値を投入して自動送信する。
        // まずフォーム送信 (done.html への遷移) が行われたことを確認し、
        // 送信自体の失敗と capture 未発火を切り分ける。
        val submitted = runCatching {
            composeRule.waitForUrlBarContains(ADDRESS_FORM_DONE_FILE_NAME, timeoutMillis = 30_000)
        }.isSuccess

        // 送信を Gecko の formautofill が検出すると onAddressSave プロンプトが発火し、
        // AddressSaveDialog が表示されるはず。
        try {
            composeRule.waitUntil(timeoutMillis = 60_000) {
                composeRule
                    .onAllNodesWithTag(BrowserTabDialogLayerTestTags.AddressSaveDialog.testTag)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "住所保存ダイアログが表示されない (フォーム送信=$submitted)\n" +
                    "--- logcat (formautofill関連) ---\n${collectFormAutofillLogcat()}",
                e,
            )
        }
    }

    /**
     * formautofill 関連の logcat を収集する。失敗時の診断用。
     */
    private fun collectFormAutofillLogcat(): String {
        val output = runCatching {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("logcat -d")
            ParcelFileDescriptor.AutoCloseInputStream(pfd)
                .bufferedReader()
                .readLines()
        }.getOrElse { return "logcat取得失敗: $it" }
        return output
            .filter { line ->
                listOf("autofill", "GeckoConsole", "GeckoViewPrompt", "prompt", "addr-test")
                    .any { line.contains(it, ignoreCase = true) }
            }
            .takeLast(LOGCAT_TAIL_LINES)
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
        awaitGeckoResult {
            GeckoPreferenceController.setGeckoPref(
                "extensions.formautofill.skipProgrammaticCheckForTests",
                true,
                GeckoPreferenceController.PREF_BRANCH_USER,
            )
        }
        // 失敗時の診断のため formautofill の Debug ログを logcat (GeckoConsole) に出す
        awaitGeckoResult {
            GeckoPreferenceController.setGeckoPref(
                "extensions.formautofill.loglevel",
                "Debug",
                GeckoPreferenceController.PREF_BRANCH_USER,
            )
        }
        // テストページの console.log を logcat に出してページ内 JS の進行を確認できるようにする
        awaitGeckoResult {
            GeckoPreferenceController.setGeckoPref(
                "geckoview.console.enabled",
                true,
                GeckoPreferenceController.PREF_BRANCH_USER,
            )
        }
        awaitGeckoResult {
            GeckoPreferenceController.setGeckoPref(
                "devtools.console.stdout.content",
                true,
                GeckoPreferenceController.PREF_BRANCH_USER,
            )
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
                <form id="address-form" method="get" action="/$ADDRESS_FORM_DONE_FILE_NAME">
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
        private val serverSocket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())

        val port: Int get() = serverSocket.localPort

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
        private const val ADDRESS_FORM_DONE_FILE_NAME = "done.html"
        private const val PREF_TIMEOUT_MILLIS = 30_000L
        private const val PREF_POLL_TIMEOUT_MILLIS = 5_000L
        private const val LOGCAT_TAIL_LINES = 120
    }
}
