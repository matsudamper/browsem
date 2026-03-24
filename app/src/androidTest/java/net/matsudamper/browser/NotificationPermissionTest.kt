package net.matsudamper.browser

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * サイトが通知パーミッションを要求したとき、ユーザーが「許可」を選択すると
 * Notification.requestPermission() が "granted" を返すことを検証するテスト。
 *
 * 正常な動作:
 *   1. POST_NOTIFICATIONS が未許可の状態でサイトが通知を要求する
 *   2. アプリ内のサイト別通知許可ダイアログ（「通知の許可」）が表示される
 *   3. ユーザーが「許可」を押す
 *   4. Android の OS パーミッションダイアログが表示される
 *   5. ユーザーが「許可」を押す
 *   6. Notification.requestPermission() のコールバックが "granted" で呼ばれる
 *
 * このテストが失敗する場合、以下のいずれかを示す:
 *   - onContentPermissionRequest デリゲートが呼ばれていない（インアプリダイアログ未表示）
 *   - GeckoResult が解決されていない（タイムアウト）
 *   - 許可したにも関わらず "denied" が返されている（実装バグ）
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * POST_NOTIFICATIONS を明示的に revoke してから通知パーミッションフローを実行し、
     * 「許可」後に "granted" が返ることを検証する。
     */
    @Test
    fun allowingNotificationPermissionShouldReturnGranted() {
        // POST_NOTIFICATIONS を revoke して「OSダイアログが必ず表示される」状態にする
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.revokeRuntimePermission(
                composeRule.activity.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }

        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)
        val pageUri = prepareLocalNotificationPermissionPageUri()

        // テストページを開く
        composeRule.runOnIdle {
            activeTab.session.loadUri(pageUri)
        }

        // ページが読み込まれるまで待機する
        composeRule.waitUntil(timeoutMillis = 30_000) {
            var matched = false
            composeRule.runOnIdle {
                matched = activeTab.currentUrl.contains(LOCAL_NOTIFICATION_PERMISSION_DIR_NAME)
            }
            matched
        }

        // JS でボタンをクリックして Notification.requestPermission() を呼び出す
        composeRule.runOnIdle {
            activeTab.session.loadUri(
                "javascript:void(document.getElementById('request-btn').click())"
            )
        }

        // インアプリのサイト別通知許可ダイアログが表示されるまで待機し、「許可」をクリックする。
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("通知の許可")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("許可").performClick()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

            // OS パーミッションダイアログが表示されることを確認する。
            // 表示されない場合、onContentPermissionRequest デリゲートが呼ばれていないか、
            // GeckoResult が resolve される前にダイアログが出ないことを示す。
            val allowButton = device.wait(
                Until.findObject(
                    By.res("com.android.permissioncontroller:id/permission_allow_button")
                ),
                10_000L,
            )
            assertNotNull(
                "OS の通知パーミッションダイアログが表示されませんでした。" +
                    "onContentPermissionRequest デリゲートが呼ばれていないか、" +
                    "POST_NOTIFICATIONS の revoke が反映されていない可能性があります。",
                allowButton,
            )
            allowButton!!.click()
        }

        // GeckoResult が解決されると JS の Notification.requestPermission() コールバックが呼ばれ、
        // タブのタイトルが "notification-result:granted" に更新される。
        composeRule.waitUntil(timeoutMillis = 15_000) {
            var matched = false
            composeRule.runOnIdle {
                matched = activeTab.title.startsWith("notification-result:")
            }
            matched
        }

        // 「許可」を押した後は必ず "granted" でなければならない。
        // "denied" が返る場合、GeckoResult が正しい値で resolve されていないことを示す。
        composeRule.runOnIdle {
            assertEquals(
                "通知を「許可」した後は 'notification-result:granted' を期待しましたが " +
                    "'${activeTab.title}' でした。" +
                    "onContentPermissionRequest の GeckoResult が VALUE_ALLOW で resolve されていない可能性があります。",
                "notification-result:granted",
                activeTab.title,
            )
        }
    }

    private fun waitForBrowserSessionController(): BrowserSessionController {
        var controller: BrowserSessionController? = null
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var resolved = false
            composeRule.runOnIdle {
                resolved = runCatching {
                    controller = ViewModelProvider(composeRule.activity)[BrowserViewModel::class.java]
                        .browserSessionController
                }.isSuccess
            }
            resolved
        }
        return requireNotNull(controller)
    }

    private fun waitForActiveTab(browserSessionController: BrowserSessionController): BrowserTab {
        var activeTab: BrowserTab? = null
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var found = false
            composeRule.runOnIdle {
                activeTab = browserSessionController.tabs.firstOrNull { it.session.isOpen }
                    ?: browserSessionController.tabs.lastOrNull()
                found = activeTab != null
            }
            found
        }
        return requireNotNull(activeTab)
    }

    private fun prepareLocalNotificationPermissionPageUri(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val destinationDir =
            File(targetContext.cacheDir, LOCAL_NOTIFICATION_PERMISSION_DIR_NAME).apply { mkdirs() }
        val assetManager = instrumentation.context.assets
        val destination = File(destinationDir, LOCAL_NOTIFICATION_PERMISSION_INDEX_FILE_NAME)
        assetManager.open(
            "$LOCAL_NOTIFICATION_PERMISSION_ASSET_DIR/$LOCAL_NOTIFICATION_PERMISSION_INDEX_FILE_NAME"
        ).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return destination.toURI().toString()
    }

    companion object {
        private const val LOCAL_NOTIFICATION_PERMISSION_ASSET_DIR = "test-notification-permission"
        private const val LOCAL_NOTIFICATION_PERMISSION_DIR_NAME = "test-notification-permission"
        private const val LOCAL_NOTIFICATION_PERMISSION_INDEX_FILE_NAME = "index.html"
    }
}
