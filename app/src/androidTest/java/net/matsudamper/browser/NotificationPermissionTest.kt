package net.matsudamper.browser

import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * サイトが通知パーミッションを要求したとき、アプリ側のデリゲートが正しく応答して
 * GeckoResult を解決できるかを確認するテスト。
 *
 * 再現シナリオ:
 *   「サイト側の通知を許可するボタンを押してもダイアログが閉じられない」問題を GMD で再現する。
 *   - GeckoView の onContentPermissionRequest デリゲートが呼ばれない、または
 *     GeckoResult が解決されない場合、Notification.requestPermission() の JS コールバックが
 *     呼ばれず、タブのタイトルが変化しないためテストが失敗する。
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * テストページのボタンを押して Notification.requestPermission() を呼び出し、
     * Android の POST_NOTIFICATIONS ダイアログを「許可」した後に
     * JS コールバックが呼ばれてタブタイトルが更新されることを確認する。
     *
     * デリゲートが未実装または GeckoResult が解決されない場合は waitUntil がタイムアウトして失敗する。
     */
    @Test
    fun pressingNotificationAllowButtonCompletesPermissionFlow() {
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
                "javascript:document.getElementById('request-btn').click()"
            )
        }

        // Android 13+ では POST_NOTIFICATIONS 許可ダイアログが表示されるので許可ボタンを押す
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            // 許可ボタンを最大 8 秒待って押す
            val allowButton = device.wait(
                Until.findObject(
                    By.res("com.android.permissioncontroller:id/permission_allow_button")
                ),
                8_000L,
            )
            allowButton?.click()
        }

        // GeckoResult が解決されると JS の Notification.requestPermission() コールバックが呼ばれ、
        // タブのタイトルが "notification-result:granted" または "notification-result:denied" に更新される。
        // タイムアウトした場合、onContentPermissionRequest デリゲートが未実装か、
        // GeckoResult が解決されていないことを示す。
        composeRule.waitUntil(timeoutMillis = 15_000) {
            var matched = false
            composeRule.runOnIdle {
                matched = activeTab.title.startsWith("notification-result:")
            }
            matched
        }

        composeRule.runOnIdle {
            assertTrue(
                "通知パーミッションフローが完了しませんでした。" +
                    "onContentPermissionRequest デリゲートが呼ばれないか、GeckoResult が解決されていない可能性があります。" +
                    " (title=${activeTab.title})",
                activeTab.title == "notification-result:granted" ||
                    activeTab.title == "notification-result:denied",
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
