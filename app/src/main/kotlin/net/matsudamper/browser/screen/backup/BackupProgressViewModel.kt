package net.matsudamper.browser.screen.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.BackupRepository
import net.matsudamper.browser.ui.settings.BackupProgressUiState

internal class BackupProgressViewModel(
    private val isImport: Boolean,
    private val backupRepository: BackupRepository,
) : ViewModel() {

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    // onCleared 通過済みフラグ。NonCancellable 処理が onCleared 後に完了した場合のフォールバックに使用する
    @Volatile
    private var cleared = false

    private val _phaseFlow = MutableStateFlow<BackupProgressUiState.Phase>(
        BackupProgressUiState.Phase.Confirming,
    )

    private val callbacks = object : BackupProgressUiState.Callbacks {
        override fun onConfirm() {
            // ファイルピッカーを要求するイベントを送信する。
            // フェーズは Confirming のまま維持し、URI 取得後に startWithUri で InProgress へ遷移する
            eventHandler.trySend { it.onRequestFilePicker() }
        }

        override fun onCancel() {
            eventHandler.trySend { it.onNavigateBack() }
        }

        override fun onDismiss() {
            eventHandler.trySend { it.onNavigateBack() }
        }

        override fun onRestart() {
            eventHandler.trySend { it.onRestartApp() }
        }
    }

    val uiState: StateFlow<BackupProgressUiState> = MutableStateFlow(
        BackupProgressUiState(
            isImport = isImport,
            phase = BackupProgressUiState.Phase.Confirming,
            callbacks = callbacks,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            _phaseFlow.collect { phase ->
                uiStateFlow.update { it.copy(phase = phase) }
            }
        }
    }.asStateFlow()

    /**
     * ファイルピッカーで取得した URI を渡してバックアップ処理を開始する。
     * フェーズを InProgress に変更し、エクスポートまたはインポートを実行する。
     */
    fun startWithUri(uri: Uri) {
        viewModelScope.launch {
            _phaseFlow.update { BackupProgressUiState.Phase.InProgress }
            if (isImport) {
                runImport(uri)
            } else {
                runExport(uri)
            }
        }
    }

    /**
     * エクスポートを実行する。
     */
    private suspend fun runExport(uri: Uri) {
        val result = try {
            backupRepository.exportToZip(uri)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
        result.fold(
            onSuccess = {
                _phaseFlow.update {
                    BackupProgressUiState.Phase.Completed("バックアップを書き出しました")
                }
            },
            onFailure = { e ->
                _phaseFlow.update {
                    BackupProgressUiState.Phase.Error(
                        message = "エクスポートに失敗しました: ${e.message ?: e::class.simpleName}",
                        pendingRestart = false,
                    )
                }
            },
        )
    }

    /**
     * インポートを実行する。
     * DB クローズ後の失敗は必ず再起動が必要なため NonCancellable で囲む。
     */
    private suspend fun runImport(uri: Uri) {
        // インポート中に画面遷移で viewModelScope がキャンセルされると、
        // TabDatabase.closeInstance() 後のファイル置換が中途半端に終わり
        // アプリが degraded 状態になる可能性がある。NonCancellable で最後まで完了させる。
        withContext(NonCancellable) {
            try {
                backupRepository.importFromZip(uri)
                _phaseFlow.update { BackupProgressUiState.Phase.PendingRestart(errorMessage = null) }
                forceRestartIfDetached()
            } catch (e: BackupRepository.RestartRequiredException) {
                // DB クローズ後の失敗。通常動作には戻れないため再起動ダイアログを出す
                val errorMessage = "復元中にエラーが発生しました: " +
                    "${e.cause?.message ?: e.message ?: e::class.simpleName}。" +
                    "アプリを終了します"
                _phaseFlow.update {
                    BackupProgressUiState.Phase.PendingRestart(errorMessage = errorMessage)
                }
                forceRestartIfDetached()
            } catch (t: Throwable) {
                _phaseFlow.update {
                    BackupProgressUiState.Phase.Error(
                        message = "復元に失敗しました: ${t.message ?: t::class.simpleName}",
                        pendingRestart = false,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleared = true
        // インポート成功後に画面が破棄された場合でも確実に再起動を発火する
        val phase = _phaseFlow.value
        if (phase is BackupProgressUiState.Phase.PendingRestart) {
            killSelfProcess()
        }
    }

    /**
     * NonCancellable 処理が onCleared 通過後に完了した場合のフォールバック。
     * cleared フラグを確認して直接プロセスを終了する。
     */
    private fun forceRestartIfDetached() {
        if (cleared) {
            killSelfProcess()
        }
    }

    private fun killSelfProcess() {
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    interface Event {
        /** ファイルピッカーを開くよう要求する */
        fun onRequestFilePicker()

        /** アプリを再起動（プロセス終了）する */
        fun onRestartApp()

        /** 前の画面に戻る */
        fun onNavigateBack()
    }
}
