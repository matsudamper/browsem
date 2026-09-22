package net.matsudamper.browser.media

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.matsudamper.browser.AutoplayPermissionDialogTestTags
import net.matsudamper.browser.GeckoBrowserTabTestTags
import net.matsudamper.browser.LocalHttpServer
import net.matsudamper.browser.MainActivity
import net.matsudamper.browser.feature.media.MediaPlaybackService
import net.matsudamper.browser.feature.media.MediaSessionBridge
import net.matsudamper.browser.openUrlViaViewIntent
import net.matsudamper.browser.waitForUrlBarContains
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * メディア通知機能のスモークテスト。
 *
 * ローカル動画ページを開いてユーザー操作で再生を開始し、
 * メディアタイトルとコントロールを持つ通知が NotificationManager に投稿されることを確認する。
 *
 * GMD の ATD イメージは SystemUI が取り除かれており通知シェードが存在しないため、
 * UiAutomator でシェード上の表示を探すのではなく、自アプリの投稿済み通知を直接読む。
 */
@RunWith(AndroidJUnit4::class)
class MediaNotificationSmokeTest {
    private var localHttpServer: LocalHttpServer? = null

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val timeoutRule: Timeout = Timeout.millis(TEST_TIMEOUT_MS)

    private val activity: MainActivity get() = composeRule.activity

    /** 自動再生の確認ダイアログを閉じた回数。失敗時の診断に使う */
    private var autoplayDialogDismissCount = 0

    @After
    fun tearDown() {
        localHttpServer?.close()
        localHttpServer = null
        // GeckoViewのメディア再生中にメインスレッドが応答しない場合でもテストがブロックしないよう、
        // CountDownLatchで最大5秒まで待機する非同期後始末にする。
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching { MediaSessionBridge.deactivate() }
            runCatching { activity.stopService(Intent(activity, MediaPlaybackService::class.java)) }
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    /**
     * ローカル HTTP サーバーが配信する test-media/index.html を開き、
     * 画面タップで再生開始した後にメディア通知が投稿されることを確認する。
     */
    @Test
    fun ローカル動画再生でメディア通知が表示される() {
        val mediaPageUri = startMediaPageServer()
        val uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        openMediaPage(mediaPageUri)

        Log.d(TAG, "ページオープン完了: package=${uiDevice.currentPackageName}")

        // ユーザー操作で再生を開始する（自動再生制限の影響を避ける）。
        // 固定時間の sleep だとページロードが遅い環境でタップが空振りしたまま通知確認へ進んで
        // flaky になるため、タップ後に MediaSessionBridge の isPlaying をポーリングし、
        // 実際に再生が始まるまでタップを繰り返す。
        val playbackStarted = startPlaybackByTapping()
        val stateAfterTap = MediaSessionBridge.playbackState.value
        Log.d(TAG, "再生開始確認: started=$playbackStarted state=$stateAfterTap")

        val mediaNotification = waitForMediaNotification()
        Log.d(TAG, "通知検索完了: 発見=${mediaNotification != null}, タイトル=\"$EXPECTED_TITLE\", タイムアウト=${NOTIFICATION_CONTROL_TIMEOUT_MS}ms")
        if (mediaNotification == null) {
            // CI は日本語メソッド名の logcat を紐付けられないため、原因切り分けに必要な情報を
            // assert メッセージへ含める。
            val diagnostics = collectNotificationDiagnostics(uiDevice)
            Log.d(TAG, "通知未検出の診断情報: $diagnostics")
            fail(
                "通知タイトル \"$EXPECTED_TITLE\" の通知が ${NOTIFICATION_CONTROL_TIMEOUT_MS}ms 以内に投稿されなかった " +
                    "(再生開始=$playbackStarted, 自動再生ダイアログ却下回数=$autoplayDialogDismissCount, " +
                    "再生状態=$stateAfterTap) $diagnostics",
            )
        }
        val actions = mediaNotification?.notification?.actions.orEmpty()
        assertTrue(
            "メディア通知に再生コントロールが含まれていない (actions=${actions.map { it.title }})",
            actions.isNotEmpty(),
        )
    }

    // ================================================================
    // ヘルパーメソッド
    // ================================================================

    /**
     * GeckoView をタップして動画再生を開始し、MediaSessionBridge の isPlaying が
     * true になるまで待つ。再生が確認できたら true を返す。
     *
     * 座標タップではなく Compose セマンティクス経由でタップする。index.html は
     * autoplay 属性を持ち自動再生の確認ダイアログが前面に出るため、座標タップだと
     * ダイアログに吸われて動画へ届かない。セマンティクス経由なら対象ノードへ直接
     * ディスパッチされるのでダイアログの有無に影響されない。
     *
     * ページロード完了前のタップは空振りするため、試行ごとにタップし直す。
     * 再生が始まらないまま試行上限に達した場合も false を返して通知確認へ進み、
     * 失敗時は assert メッセージの診断情報から原因を特定できるようにする。
     */
    private fun startPlaybackByTapping(): Boolean {
        repeat(PLAYBACK_TAP_RETRY_COUNT) { index ->
            // ダイアログを開いたままにすると通知シェードの確認と干渉するため、出ていれば閉じる。
            // 「却下」してもユーザー操作による再生はブロックされない。
            dismissAutoplayPermissionDialogIfShown()
            Log.d(TAG, "タップ試行${index + 1}/${PLAYBACK_TAP_RETRY_COUNT}")
            runCatching {
                composeRule.onNodeWithTag(GeckoBrowserTabTestTags.GeckoContainer.testTag)
                    .performTouchInput { click() }
            }.onFailure { Log.d(TAG, "タップ失敗: ${it.message}") }
            val deadline = SystemClock.elapsedRealtime() + PLAYBACK_START_CONFIRM_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (MediaSessionBridge.playbackState.value.isPlaying) {
                    Log.d(TAG, "タップ試行${index + 1}で再生開始を確認")
                    return true
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            Log.d(TAG, "タップ試行${index + 1}完了: 再生未開始 state=${MediaSessionBridge.playbackState.value}")
        }
        return MediaSessionBridge.playbackState.value.isPlaying
    }

    /**
     * 自動再生の確認ダイアログが表示されていれば「却下」を押して閉じる。
     * 表示されていなければ何もしない。閉じたかどうかを [autoplayDialogDismissCount] に記録し、
     * 失敗時の診断に使う。
     */
    private fun dismissAutoplayPermissionDialogIfShown() {
        val denyNode = composeRule
            .onAllNodesWithTag(AutoplayPermissionDialogTestTags.Deny.testTag)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        if (denyNode.isEmpty()) return
        Log.d(TAG, "自動再生の確認ダイアログを却下で閉じる")
        autoplayDialogDismissCount += 1
        runCatching {
            composeRule.onNodeWithTag(AutoplayPermissionDialogTestTags.Deny.testTag).performClick()
            composeRule.waitForIdle()
        }.onFailure { Log.d(TAG, "ダイアログ却下に失敗: ${it.message}") }
    }

    /**
     * 自アプリの投稿済み通知から、タイトルが [EXPECTED_TITLE] のものが現れるまで待って返す。
     * 見つからないまま [NOTIFICATION_CONTROL_TIMEOUT_MS] を超えたら null を返す。
     */
    private fun waitForMediaNotification(): StatusBarNotification? {
        val notificationManager = activity.getSystemService(NotificationManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + NOTIFICATION_CONTROL_TIMEOUT_MS
        while (true) {
            val found = notificationManager.activeNotifications.firstOrNull { statusBarNotification ->
                statusBarNotification.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == EXPECTED_TITLE
            }
            if (found != null || SystemClock.elapsedRealtime() >= deadline) {
                return found
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    /**
     * 通知が見つからなかったときの切り分け用に、システム側の通知レコードと自アプリの通知設定を集める。
     * 通知が投稿されていないのか、投稿されているのにアプリ側から見えないのかを区別する。
     */
    private fun collectNotificationDiagnostics(uiDevice: UiDevice): String {
        val postedNotifications = runCatching {
            uiDevice.executeShellCommand("dumpsys notification --noredact")
                .lineSequence()
                .filter { line ->
                    line.contains(activity.packageName) || line.contains(EXPECTED_TITLE) || line.contains("NotificationRecord")
                }
                .joinToString("|") { it.trim() }
                .take(DIAGNOSTICS_MAX_LENGTH)
        }.getOrElse { "dumpsys失敗: ${it.message}" }
        return "[通知ダンプ: $postedNotifications]"
    }

    private fun openMediaPage(mediaPageUri: String) {
        composeRule.openUrlViaViewIntent(mediaPageUri)
        composeRule.waitForUrlBarContains(LOCAL_MEDIA_INDEX_FILE_NAME, timeoutMillis = PAGE_LOAD_TIMEOUT_MS)
    }

    /**
     * テスト用メディアページをキャッシュへ展開し、
     * ループバック HTTP サーバーから配信してその URL を返す。
     *
     * メディア通知はビルトイン拡張のコンテンツスクリプトが送る再生状態から生成される。
     * GeckoView 153 以降は拡張機能が file URL へアクセスできないため、
     * file URL のままでは再生状態を取得できない(LocalHttpServer 参照)。
     */
    private fun startMediaPageServer(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val destinationDir = File(targetContext.cacheDir, LOCAL_MEDIA_DIR_NAME).apply { mkdirs() }
        val assetManager = instrumentation.context.assets
        assetManager.list(LOCAL_MEDIA_ASSET_DIR)?.forEach { name ->
            val destination = File(destinationDir, name)
            assetManager.open("$LOCAL_MEDIA_ASSET_DIR/$name").use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        val server = LocalHttpServer(destinationDir)
        localHttpServer = server
        return server.url(LOCAL_MEDIA_INDEX_FILE_NAME)
    }

    companion object {
        private const val TAG = "MediaNotificationSmoke"
        private const val TEST_TIMEOUT_MS = 180_000L
        private const val PAGE_LOAD_TIMEOUT_MS = 60_000L

        // ページロード待ちを兼ねるため、タップ試行は多め・確認間隔は短めに設定する
        private const val PLAYBACK_TAP_RETRY_COUNT = 10
        private const val PLAYBACK_START_CONFIRM_TIMEOUT_MS = 2_500L
        private const val POLL_INTERVAL_MS = 100L
        private const val NOTIFICATION_CONTROL_TIMEOUT_MS = 15_000L
        private const val LOCAL_MEDIA_ASSET_DIR = "test-media"
        private const val LOCAL_MEDIA_DIR_NAME = "test-media"
        private const val LOCAL_MEDIA_INDEX_FILE_NAME = "index.html"
        private const val EXPECTED_TITLE = "Test Video"
        private const val DIAGNOSTICS_MAX_LENGTH = 3_000
    }
}
