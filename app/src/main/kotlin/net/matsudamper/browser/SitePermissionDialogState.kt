package net.matsudamper.browser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import net.matsudamper.browser.data.SitePermissionState
import net.matsudamper.browser.data.SiteSettingsRepository

/**
 * サイトごとの権限（マイク・自動再生）の確認ダイアログ状態。
 *
 * 未設定のサイトではダイアログでユーザーの応答を待ち、選択をサイト設定へ永続化する。
 */
@Stable
internal class SitePermissionDialogState(
    private val siteSettingsRepository: SiteSettingsRepository,
) {
    /**
     * @param onResult true=許可(永続化), false=ブロック(永続化), null=今回のみ拒否
     */
    @Stable
    class MicrophoneDialog(
        val host: String,
        internal val onResult: (Boolean?) -> Unit,
    )

    /** 自動再生確認ダイアログでの選択 */
    enum class AutoplayChoice {
        /** 許可してサイト設定へ永続化する */
        Allow,

        /** 今回だけ許可し、永続化しない（次回も確認する） */
        AllowOnce,

        /** 却下してサイト設定へ永続化する */
        Deny,
    }

    /**
     * @param onResult 選択結果。null はダイアログを閉じただけ（今回のみ拒否）
     */
    @Stable
    class AutoplayDialog(
        val host: String,
        internal val onResult: (AutoplayChoice?) -> Unit,
    )

    var microphoneDialog by mutableStateOf<MicrophoneDialog?>(null)
        private set

    var autoplayDialog by mutableStateOf<AutoplayDialog?>(null)
        private set

    fun confirmMicrophone(allow: Boolean) {
        val dialog = microphoneDialog ?: return
        microphoneDialog = null
        dialog.onResult(allow)
    }

    fun dismissMicrophone() {
        val dialog = microphoneDialog ?: return
        microphoneDialog = null
        dialog.onResult(null)
    }

    fun confirmAutoplay(choice: AutoplayChoice) {
        val dialog = autoplayDialog ?: return
        autoplayDialog = null
        dialog.onResult(choice)
    }

    fun dismissAutoplay() {
        val dialog = autoplayDialog ?: return
        autoplayDialog = null
        dialog.onResult(null)
    }

    /**
     * サイトごとのマイク権限を解決する。
     * 未設定 (ASK) の場合は確認ダイアログを表示してユーザーの応答を待ち、
     * 許可/ブロックの選択をサイト設定として永続化する。
     */
    suspend fun resolveMicrophonePermission(host: String): Boolean {
        // 要求があったことを記録し、「サイトの設定」画面にマイクの項目を表示できるようにする
        siteSettingsRepository.markMicrophonePermissionRequested(host)
        when (siteSettingsRepository.getMicrophonePermission(host)) {
            SitePermissionState.SITE_PERMISSION_ALLOW -> return true
            SitePermissionState.SITE_PERMISSION_DENY -> return false
            else -> Unit
        }
        // 表示中のダイアログが残っている場合は今回のみ拒否として閉じる
        microphoneDialog?.also { previous ->
            microphoneDialog = null
            previous.onResult(null)
        }
        val result = CompletableDeferred<Boolean?>()
        microphoneDialog = MicrophoneDialog(host) { allow ->
            result.complete(allow)
        }
        val choice = result.await()
        if (choice != null) {
            siteSettingsRepository.setMicrophonePermission(
                host = host,
                state = if (choice) {
                    SitePermissionState.SITE_PERMISSION_ALLOW
                } else {
                    SitePermissionState.SITE_PERMISSION_DENY
                },
            )
        }
        return choice == true
    }

    /**
     * サイトごとの自動再生（音声付きメディア）の許可を解決する。
     * 未設定 (ASK) の場合は確認ダイアログを表示してユーザーの応答を待つ。
     * 「許可」「却下」はサイト設定として永続化し、「今回のみ許可」と未選択は
     * 永続化しないため次回の要求でも再びダイアログを表示する。
     */
    suspend fun resolveAutoplayPermission(host: String): Boolean {
        // 要求があったことを記録し、「サイトの設定」画面に自動再生の項目を表示できるようにする
        siteSettingsRepository.markAutoplayPermissionRequested(host)
        when (siteSettingsRepository.getAutoplayPermission(host)) {
            SitePermissionState.SITE_PERMISSION_ALLOW -> return true
            SitePermissionState.SITE_PERMISSION_DENY -> return false
            else -> Unit
        }
        // 表示中のダイアログが残っている場合は今回のみ拒否として閉じる
        autoplayDialog?.also { previous ->
            autoplayDialog = null
            previous.onResult(null)
        }
        val result = CompletableDeferred<AutoplayChoice?>()
        autoplayDialog = AutoplayDialog(host) { choice ->
            result.complete(choice)
        }
        val persistedState = when (val choice = result.await()) {
            AutoplayChoice.Allow -> SitePermissionState.SITE_PERMISSION_ALLOW

            AutoplayChoice.Deny -> SitePermissionState.SITE_PERMISSION_DENY

            // 今回のみ許可・未選択は永続化せず ASK のままにして、次回も確認する
            AutoplayChoice.AllowOnce, null -> return choice == AutoplayChoice.AllowOnce
        }
        siteSettingsRepository.setAutoplayPermission(host = host, state = persistedState)
        return persistedState == SitePermissionState.SITE_PERMISSION_ALLOW
    }
}
