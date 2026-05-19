package net.matsudamper.browser.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.resources.R as ResourcesR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupProgressScreen(
    uiState: BackupProgressUiState,
    modifier: Modifier = Modifier,
) {
    // 処理中はバック操作を無効化する
    BackHandler(enabled = uiState.phase is BackupProgressUiState.Phase.InProgress) {}

    val title = if (uiState.isImport) "インポート" else "エクスポート"

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    // 処理中は戻るボタンを非表示にする
                    if (uiState.phase !is BackupProgressUiState.Phase.InProgress) {
                        IconButton(onClick = uiState.callbacks::onDismiss) {
                            Icon(
                                painter = painterResource(ResourcesR.drawable.ic_arrow_back_24dp),
                                contentDescription = "戻る",
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when (val phase = uiState.phase) {
                is BackupProgressUiState.Phase.WaitingForFile -> {
                    // ファイルピッカーが開いている間は待機表示のみ
                    CircularProgressIndicator()
                }

                is BackupProgressUiState.Phase.InProgress -> {
                    // 処理中はプログレスインジケーターを表示する
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("処理中…")
                    }
                }

                is BackupProgressUiState.Phase.Completed -> {
                    // 完了メッセージとボタンを表示する
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(phase.successMessage)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = uiState.callbacks::onDismiss) {
                            Text("閉じる")
                        }
                    }
                }

                is BackupProgressUiState.Phase.PendingRestart -> {
                    // インポート完了後の再起動促進ダイアログを表示する
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("インポートが完了しました") },
                        text = {
                            if (phase.errorMessage != null) {
                                Text(phase.errorMessage)
                            } else {
                                Text("変更を反映するためアプリを終了してください。")
                            }
                        },
                        confirmButton = {
                            Button(onClick = uiState.callbacks::onRestart) {
                                Text("アプリを終了")
                            }
                        },
                    )
                }

                is BackupProgressUiState.Phase.Error -> {
                    // エラーダイアログを表示する
                    AlertDialog(
                        onDismissRequest = uiState.callbacks::onDismiss,
                        title = { Text("エラーが発生しました") },
                        text = { Text(phase.message) },
                        confirmButton = {
                            TextButton(onClick = uiState.callbacks::onDismiss) {
                                Text("閉じる")
                            }
                        },
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BackupProgressScreenWaitingPreview() {
    BackupProgressScreen(
        uiState = BackupProgressUiState(
            isImport = false,
            phase = BackupProgressUiState.Phase.WaitingForFile,
            callbacks = object : BackupProgressUiState.Callbacks {
                override fun onDismiss() = Unit
                override fun onRestart() = Unit
            },
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun BackupProgressScreenInProgressPreview() {
    BackupProgressScreen(
        uiState = BackupProgressUiState(
            isImport = true,
            phase = BackupProgressUiState.Phase.InProgress,
            callbacks = object : BackupProgressUiState.Callbacks {
                override fun onDismiss() = Unit
                override fun onRestart() = Unit
            },
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun BackupProgressScreenCompletedPreview() {
    BackupProgressScreen(
        uiState = BackupProgressUiState(
            isImport = false,
            phase = BackupProgressUiState.Phase.Completed("バックアップを書き出しました"),
            callbacks = object : BackupProgressUiState.Callbacks {
                override fun onDismiss() = Unit
                override fun onRestart() = Unit
            },
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun BackupProgressScreenPendingRestartPreview() {
    BackupProgressScreen(
        uiState = BackupProgressUiState(
            isImport = true,
            phase = BackupProgressUiState.Phase.PendingRestart(errorMessage = null),
            callbacks = object : BackupProgressUiState.Callbacks {
                override fun onDismiss() = Unit
                override fun onRestart() = Unit
            },
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun BackupProgressScreenErrorPreview() {
    BackupProgressScreen(
        uiState = BackupProgressUiState(
            isImport = false,
            phase = BackupProgressUiState.Phase.Error(
                message = "復元に失敗しました: ファイルが見つかりません",
                pendingRestart = false,
            ),
            callbacks = object : BackupProgressUiState.Callbacks {
                override fun onDismiss() = Unit
                override fun onRestart() = Unit
            },
        ),
    )
}
