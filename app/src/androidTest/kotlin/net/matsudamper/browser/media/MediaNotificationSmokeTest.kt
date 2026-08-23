package net.matsudamper.browser.media

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import net.matsudamper.browser.AutoplayPermissionDialogTestTags
import net.matsudamper.browser.LocalHttpServer
import net.matsudamper.browser.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * メディア通知機能のスモークテスト。
 *
 * ローカル動画ページを開いてユーザー操作で再生を開始し、
 * 通知シェードにメディアタイトルとコントロールが表示されることを確認する。
 */
@RunWith(AndroidJUnit4::class)
class MediaNotificationSmokeTest {
    private lateinit var activity: MainActivity
    private var localHttpServer: LocalHttpServer? = null

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @get:Rule
    val timeoutRule: Timeout = Timeout.millis(TEST_TIMEOUT_MS)

    @Before
    fun setUp() {
        activityRule.scenario.onActivity { launchedActivity ->
            activity = launchedActivity
        }
    }

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
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    }

    /**
     * ローカル HTTP サーバーが配信する test-media/index.html を開き、
     * 画面タップで再生開始した後に通知シェードへメディア通知が表示されることを確認する。
     */
    @Test
    fun ローカル動画再生でメディア通知が表示される() {
        val mediaPageUri = startMediaPageServer()
        val uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        openMediaPage(mediaPageUri)

        val displayWidth = uiDevice.displayWidth
        val displayHeight = uiDevice.displayHeight
        Log.d(TAG, "ページオープン完了: package=${uiDevice.currentPackageName}, " +
            "画面サイズ=${displayWidth}x${displayHeight}")

        // ユーザー操作で再生を開始する（自動再生制限の影響を避ける）。
        // 固定時間の sleep だとページロードが遅い環境でタップが空振りしたまま通知確認へ進んで
        // flaky になるため、タップ後に MediaSessionBridge の isPlaying をポーリングし、
        // 実際に再生が始まるまでタップを繰り返す。
        val playbackStarted = startPlaybackByTapping(uiDevice, displayWidth / 2, displayHeight / 2)
        Log.d(
            TAG,
            "再生開始確認: started=$playbackStarted state=${MediaSessionBridge.playbackState.value}",
        )

        uiDevice.openNotification()
        val found = uiDevice.wait(Until.hasObject(By.text(EXPECTED_TITLE)), NOTIFICATION_CONTROL_TIMEOUT_MS)
        Log.d(TAG, "通知検索完了: 発見=$found, タイトル=\"$EXPECTED_TITLE\", タイムアウト=${NOTIFICATION_CONTROL_TIMEOUT_MS}ms")
        try {
            assertTrue(
                "通知タイトル \"$EXPECTED_TITLE\" が ${NOTIFICATION_CONTROL_TIMEOUT_MS}ms 以内に表示されなかった",
                found,
            )
        } finally {
            uiDevice.pressBack()
        }
    }

    // ================================================================
    // ヘルパーメソッド
    // ================================================================

    /**
     * 画面中央をタップして動画再生を開始し、MediaSessionBridge の isPlaying が
     * true になるまで待つ。再生が確認できたら true を返す。
     *
     * ページロード完了前のタップは空振りするため、試行ごとにタップし直す。
     * 再生が始まらないまま試行上限に達した場合も false を返して通知確認へ進み、
     * 失敗時は直前にログ出力した再生状態から原因（再生未開始 or 通知未表示）を特定できるようにする。
     */
    private fun startPlaybackByTapping(uiDevice: UiDevice, tapX: Int, tapY: Int): Boolean {
        repeat(PLAYBACK_TAP_RETRY_COUNT) { index ->
            // index.html は autoplay 属性を持つため自動再生の確認ダイアログが出る。
            // ダイアログは画面中央を覆いタップが動画へ届かないので、先に閉じる。
            // 「却下」を選んでもユーザー操作による再生はブロックされない。
            dismissAutoplayPermissionDialogIfShown(uiDevice)
            Log.d(TAG, "タップ試行${index + 1}/${PLAYBACK_TAP_RETRY_COUNT}: 座標=($tapX, $tapY)")
            uiDevice.click(tapX, tapY)
            val deadline = SystemClock.elapsedRealtime() + PLAYBACK_START_CONFIRM_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (MediaSessionBridge.playbackState.value.isPlaying) {
                    Log.d(TAG, "タップ試行${index + 1}で再生開始を確認")
                    return true
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            Log.d(
                TAG,
                "タップ試行${index + 1}完了: 再生未開始 package=${uiDevice.currentPackageName} " +
                    "state=${MediaSessionBridge.playbackState.value}",
            )
        }
        return MediaSessionBridge.playbackState.value.isPlaying
    }

    /**
     * 自動再生の確認ダイアログが表示されていれば「却下」を押して閉じる。
     * 表示されていなければ何もしない。
     */
    private fun dismissAutoplayPermissionDialogIfShown(uiDevice: UiDevice) {
        val denyButton = uiDevice.wait(
            Until.findObject(By.res(AutoplayPermissionDialogTestTags.Deny.testTag)),
            AUTOPLAY_DIALOG_TIMEOUT_MS,
        ) ?: return
        Log.d(TAG, "自動再生の確認ダイアログを却下で閉じる")
        denyButton.click()
        uiDevice.wait(
            Until.gone(By.res(AutoplayPermissionDialogTestTags.Deny.testTag)),
            AUTOPLAY_DIALOG_TIMEOUT_MS,
        )
    }

    private fun openMediaPage(mediaPageUri: String) {
        activityRule.scenario.onActivity { currentActivity ->
            currentActivity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse(mediaPageUri)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    setClass(currentActivity, MainActivity::class.java)
                }
            )
        }
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
        // ダイアログはページロード後に出るため、初回は少し待つ
        private const val AUTOPLAY_DIALOG_TIMEOUT_MS = 2_000L
        private const val TEST_TIMEOUT_MS = 180_000L
        // ページロード待ちを兼ねるため、タップ試行は多め・確認間隔は短めに設定する
        private const val PLAYBACK_TAP_RETRY_COUNT = 10
        private const val PLAYBACK_START_CONFIRM_TIMEOUT_MS = 2_500L
        private const val POLL_INTERVAL_MS = 100L
        private const val NOTIFICATION_CONTROL_TIMEOUT_MS = 15_000L
        private const val LOCAL_MEDIA_ASSET_DIR = "test-media"
        private const val LOCAL_MEDIA_DIR_NAME = "test-media"
        private const val LOCAL_MEDIA_INDEX_FILE_NAME = "index.html"
        private const val EXPECTED_TITLE = "Test Video"
    }
}
