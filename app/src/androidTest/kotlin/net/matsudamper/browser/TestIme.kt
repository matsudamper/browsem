package net.matsudamper.browser

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry

/**
 * [TestInputMethodService] を既定の IME として有効化/解除するヘルパー。
 *
 * GMD の aosp-atd イメージにはソフトキーボードが無いため、キーボード表示を伴う
 * テストはこのダミー IME を有効化してから実行する。
 */
internal object TestIme {
    private const val SERVICE_ID =
        "net.matsudamper.browser.androidtest/net.matsudamper.browser.TestInputMethodService"
    private const val SELECT_TIMEOUT_MILLIS = 10_000L
    private const val POLL_INTERVAL_MILLIS = 200L

    /**
     * ダミー IME を有効化して既定の IME にする。切り替わったかどうかを返す。
     */
    fun enable(): Boolean {
        // エミュレータはハードウェアキーボードを持つ扱いのため、明示的に許可しないと
        // ソフトキーボードが表示されない。
        shell("settings put secure show_ime_with_hard_keyboard 1")
        shell("ime enable $SERVICE_ID")
        shell("ime set $SERVICE_ID")

        val deadline = SystemClock.elapsedRealtime() + SELECT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (shell("settings get secure default_input_method").trim() == SERVICE_ID) {
                return true
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return false
    }

    /**
     * IME の選択状態を端末の既定へ戻す。
     */
    fun reset() {
        shell("ime reset")
        shell("settings delete secure show_ime_with_hard_keyboard")
    }

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use {
            it.readBytes().decodeToString()
        }
    }
}
