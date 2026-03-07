package net.matsudamper.browser.media

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.matsudamper.browser.BrowserSessionController
import net.matsudamper.browser.BrowserTab
import net.matsudamper.browser.BrowserViewModel
import net.matsudamper.browser.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * メディア通知機能のスモークテスト。
 *
 * assets に埋め込んだテスト動画を GeckoView で再生し、
 * GeckoView の MediaSession コールバック → MediaSessionBridge → MediaPlaybackService
 * の一連のフローを検証する。
 *
 * 前提: app/src/main/assets/test-media/index.html に動画が base64 埋め込み済みであること。
 */
@RunWith(AndroidJUnit4::class)
class MediaNotificationSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        // テスト後にブリッジとサービスをリセット
        composeRule.runOnIdle {
            MediaSessionBridge.deactivate()
            composeRule.activity.stopService(
                Intent(composeRule.activity, MediaPlaybackService::class.java),
            )
        }
    }

    /**
     * file:///android_asset/test-media/index.html を開き、
     * JavaScript でビデオ再生後に MediaSessionBridge がアクティブ＆再生中になることを確認する。
     */
    @Test
    fun ローカル動画再生でメディアセッションが起動する() {
        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)

        // テストページへ移動
        composeRule.onNodeWithTag("url_bar").performClick()
        composeRule.onNodeWithTag("url_bar")
            .performTextReplacement("file:///android_asset/test-media/index.html")
        composeRule.onNodeWithTag("url_bar").performImeAction()

        // URLバーのフォーカスが外れるまで待機（ナビゲーション開始の合図）
        composeRule.waitUntil(timeoutMillis = 10_000) { !isUrlBarFocused() }

        // GeckoView が assets の HTML を読み込む時間を確保
        Thread.sleep(5_000)

        // JavaScript でビデオを再生（autoplay ポリシー回避のため明示的にコール）
        composeRule.runOnIdle {
            activeTab.session.loadUri(
                "javascript:var v=document.getElementById('video');" +
                    "if(v){v.muted=true;v.play().catch(function(){});}",
            )
        }

        // GeckoView の onActivated が呼ばれて isActive になるまで待機
        composeRule.waitUntil(timeoutMillis = 15_000) {
            MediaSessionBridge.playbackState.value.isActive
        }
        assertTrue(
            "ビデオ再生後に MediaSessionBridge がアクティブになること",
            MediaSessionBridge.playbackState.value.isActive,
        )

        // GeckoView の onPlay が呼ばれて isPlaying になるまで待機
        composeRule.waitUntil(timeoutMillis = 5_000) {
            MediaSessionBridge.playbackState.value.isPlaying
        }
        assertTrue(
            "MediaSessionBridge の isPlaying が true になること",
            MediaSessionBridge.playbackState.value.isPlaying,
        )
    }

    /**
     * 再生後に JavaScript で pause を呼ぶと isPlaying が false になることを確認する。
     */
    @Test
    fun 再生中に一時停止するとisPlayingがfalseになる() {
        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)

        // テストページへ移動して再生
        composeRule.onNodeWithTag("url_bar").performClick()
        composeRule.onNodeWithTag("url_bar")
            .performTextReplacement("file:///android_asset/test-media/index.html")
        composeRule.onNodeWithTag("url_bar").performImeAction()
        composeRule.waitUntil(timeoutMillis = 10_000) { !isUrlBarFocused() }
        Thread.sleep(5_000)

        composeRule.runOnIdle {
            activeTab.session.loadUri(
                "javascript:var v=document.getElementById('video');" +
                    "if(v){v.muted=true;v.play().catch(function(){});}",
            )
        }
        composeRule.waitUntil(timeoutMillis = 15_000) {
            MediaSessionBridge.playbackState.value.isPlaying
        }

        // 一時停止
        composeRule.runOnIdle {
            activeTab.session.loadUri(
                "javascript:var v=document.getElementById('video');" +
                    "if(v)v.pause();",
            )
        }

        // isPlaying が false になるまで待機
        composeRule.waitUntil(timeoutMillis = 5_000) {
            !MediaSessionBridge.playbackState.value.isPlaying
        }
        assertTrue(
            "一時停止後に isPlaying が false になること",
            !MediaSessionBridge.playbackState.value.isPlaying,
        )
        // セッション自体はまだアクティブであること
        assertTrue(
            "一時停止後もメディアセッションはアクティブなままであること",
            MediaSessionBridge.playbackState.value.isActive,
        )
    }

    // ================================================================
    // ヘルパーメソッド
    // ================================================================

    private fun waitForBrowserSessionController(): BrowserSessionController {
        var controller: BrowserSessionController? = null
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var resolved = false
            composeRule.runOnIdle {
                resolved = runCatching {
                    controller = getBrowserViewModel().browserSessionController
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

    private fun getBrowserViewModel(): BrowserViewModel {
        return ViewModelProvider(composeRule.activity)[BrowserViewModel::class.java]
    }

    private fun isUrlBarFocused(): Boolean {
        return runCatching {
            composeRule.onNodeWithTag("url_bar")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Focused]
        }.getOrDefault(false)
    }
}
