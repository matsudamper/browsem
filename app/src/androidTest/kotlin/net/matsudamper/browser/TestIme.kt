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
    private val DUMP_KEYS = listOf(
        "mInputShown",
        "mIsInputViewShown",
        "mImeWindowVis",
        "mCurMethodId",
        "mServedView",
        "mShowRequested",
    )
    private const val DUMP_MAX_LINES = 20

    private var previousDefaultIme: String? = null
    private var previousShowImeWithHardKeyboard: String? = null

    /**
     * ダミー IME を有効化して既定の IME にする。切り替わったかどうかを返す。
     *
     * 後続のテストへ設定が波及しないよう、変更前の値を控えておく。
     */
    fun enable(): Boolean {
        previousDefaultIme = readSetting("default_input_method")
        previousShowImeWithHardKeyboard = readSetting("show_ime_with_hard_keyboard")

        // エミュレータはハードウェアキーボードを持つ扱いのため、明示的に許可しないと
        // ソフトキーボードが表示されない。
        shell("settings put secure show_ime_with_hard_keyboard 1")
        shell("ime enable $SERVICE_ID")
        shell("ime set $SERVICE_ID")

        val deadline = SystemClock.elapsedRealtime() + SELECT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (readSetting("default_input_method") == SERVICE_ID) {
                return true
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return false
    }

    /**
     * enable() で変更した IME 設定を元の値へ戻す。
     */
    fun reset() {
        shell("ime disable $SERVICE_ID")
        previousDefaultIme?.let { shell("ime set $it") }
        previousDefaultIme = null

        when (val previous = previousShowImeWithHardKeyboard) {
            null -> shell("settings delete secure show_ime_with_hard_keyboard")
            else -> shell("settings put secure show_ime_with_hard_keyboard $previous")
        }
        previousShowImeWithHardKeyboard = null
    }

    /**
     * secure 設定を読む。未設定なら null を返す。
     */
    private fun readSetting(key: String): String? {
        return shell("settings get secure $key").trim()
            .takeIf { it.isNotEmpty() && it != "null" }
    }

    /**
     * IME が表示されない場合の診断用に、input_method の状態を抜粋して返す。
     */
    fun diagnostics(): String {
        val defaultIme = readSetting("default_input_method")
        val dump = shell("dumpsys input_method")
            .lineSequence()
            .filter { line -> DUMP_KEYS.any { line.contains(it) } }
            .take(DUMP_MAX_LINES)
            .joinToString(separator = "\n") { it.trim() }
        return "default_input_method=$defaultIme\n$dump"
    }

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use {
            it.readBytes().decodeToString()
        }
    }
}
