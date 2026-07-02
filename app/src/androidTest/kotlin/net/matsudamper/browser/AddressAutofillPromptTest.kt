package net.matsudamper.browser

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * GeckoView の住所フォーム自動入力 (formautofill) が実際に動作することを確認するテスト。
 *
 * geckoview-config.yaml で有効化した extensions.formautofill.addresses.capture.enabled により、
 * 住所フォーム送信時に PromptDelegate.onAddressSave が発火して保存ダイアログが表示されることを検証する。
 * フォームへの値の投入と送信はページ内 JavaScript で行うため、Web コンテンツへの直接操作は行わない。
 */
@RunWith(AndroidJUnit4::class)
class AddressAutofillPromptTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun submittingAddressFormShowsAddressSaveDialog() {
        val pageUri = prepareAddressFormPageUri()

        composeRule.openUrlFromUrlBar(pageUri)
        composeRule.waitForUrlBarContains(ADDRESS_FORM_FILE_NAME, timeoutMillis = 60_000)

        // ページは load 後にフォームへ値を投入して自動送信する。
        // 送信を Gecko の formautofill が検出すると onAddressSave プロンプトが発火し、
        // AddressSaveDialog が表示されるはず。
        composeRule.waitUntil(timeoutMillis = 90_000) {
            composeRule
                .onAllNodesWithTag(BrowserTabDialogLayerTestTags.AddressSaveDialog.testTag)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /**
     * 住所フォームのローカルHTMLをキャッシュへ生成し、file URI を返す。
     *
     * formautofill の対象判定はロケール・地域に依存するため、確実に対象となる US 形式の住所を使う。
     */
    private fun prepareAddressFormPageUri(): String {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val destinationDir = File(targetContext.cacheDir, ADDRESS_FORM_DIR_NAME).apply { mkdirs() }
        File(destinationDir, ADDRESS_FORM_DONE_FILE_NAME).writeText(
            """
            <!doctype html>
            <html lang="en">
              <head><meta charset="utf-8" /><title>Done</title></head>
              <body><main>Submitted</main></body>
            </html>
            """.trimIndent(),
        )
        val destination = File(destinationDir, ADDRESS_FORM_FILE_NAME)
        destination.writeText(
            """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8" />
                <title>Address Form Test</title>
              </head>
              <body>
                <form id="address-form" method="get" action="$ADDRESS_FORM_DONE_FILE_NAME">
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
                  function setValue(id, value) {
                    const el = document.getElementById(id);
                    el.focus();
                    el.value = value;
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                  }
                  window.addEventListener('load', () => {
                    // formautofill のフィールド検出が完了するのを待ってから投入・送信する
                    setTimeout(() => {
                      setValue('given-name', 'John');
                      setValue('family-name', 'Doe');
                      setValue('organization', 'Example Inc');
                      setValue('street-address', '123 Main Street');
                      setValue('address-level2', 'Mountain View');
                      setValue('address-level1', 'CA');
                      setValue('postal-code', '94043');
                      setValue('tel', '+16505551234');
                      setValue('email', 'john.doe@example.com');
                      document.getElementById('submit-button').click();
                    }, 3000);
                  });
                </script>
              </body>
            </html>
            """.trimIndent(),
        )
        return destination.toURI().toString()
    }

    private companion object {
        private const val ADDRESS_FORM_DIR_NAME = "test-address-form"
        private const val ADDRESS_FORM_FILE_NAME = "address-form.html"
        private const val ADDRESS_FORM_DONE_FILE_NAME = "done.html"
    }
}
