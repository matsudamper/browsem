package net.matsudamper.browser

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import kotlin.time.Duration

/**
 * デバッグ時にユーザーに操作させて確認したい場合に差し込む
 */
@Suppress("unused")
internal fun AndroidComposeTestRule<*, *>.waitDebugUserInteractionInfinity() {
    while (true) {
        Thread.sleep(1_000)
        waitForIdle()
    }
}

@Suppress("unused")
internal fun AndroidComposeTestRule<*, *>.delay(duration: Duration) {
    Thread.sleep(duration.inWholeMilliseconds)
    waitForIdle()
}
