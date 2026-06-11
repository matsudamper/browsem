package net.matsudamper.browser.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.data.SiteGeolocationState
import net.matsudamper.browser.data.SitePermissionState
import net.matsudamper.browser.resources.R as ResourcesR

/**
 * サイトごとの設定画面。
 * 今後マイク以外の設定項目も追加するため、画面タイトルは「サイトの設定」の汎用名にしている。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteSettingsScreen(
    uiState: SiteSettingsScreenUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val currentCallbacks by rememberUpdatedState(uiState.callbacks)
    val clearDataResultMessage = uiState.clearDataResultMessage

    LaunchedEffect(clearDataResultMessage) {
        if (clearDataResultMessage != null) {
            snackbarHostState.showSnackbar(clearDataResultMessage)
            currentCallbacks.consumeClearDataResultMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("サイトの設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_arrow_back_24dp),
                            contentDescription = "戻る",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(
                    start = paddingValues.calculateStartPadding(LocalLayoutDirection.current),
                    end = paddingValues.calculateStartPadding(LocalLayoutDirection.current),
                    top = paddingValues.calculateTopPadding(),
                )
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                text = uiState.host,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(12.dp))

            // 権限は一度でも要求された項目だけ表示する
            if (uiState.geolocationState != null) {
                SettingSection(title = "位置情報") {
                    Column(Modifier.selectableGroup()) {
                        SettingsRadioOption(
                            label = "モック位置情報",
                            selected = uiState.geolocationState == SiteGeolocationState.SITE_GEOLOCATION_MOCK,
                            onClick = {
                                uiState.callbacks.setGeolocationState(
                                    SiteGeolocationState.SITE_GEOLOCATION_MOCK,
                                )
                            },
                        )
                        SettingsRadioOption(
                            label = "実際の位置情報",
                            selected = uiState.geolocationState == SiteGeolocationState.SITE_GEOLOCATION_REAL,
                            onClick = {
                                uiState.callbacks.setGeolocationState(
                                    SiteGeolocationState.SITE_GEOLOCATION_REAL,
                                )
                            },
                        )
                        SettingsRadioOption(
                            label = "ブロック",
                            selected = uiState.geolocationState == SiteGeolocationState.SITE_GEOLOCATION_DENY,
                            onClick = {
                                uiState.callbacks.setGeolocationState(
                                    SiteGeolocationState.SITE_GEOLOCATION_DENY,
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (uiState.microphonePermission != null) {
                SettingSection(title = "マイク") {
                    Column(Modifier.selectableGroup()) {
                        SettingsRadioOption(
                            label = "確認する",
                            selected = uiState.microphonePermission == SitePermissionState.SITE_PERMISSION_ASK,
                            onClick = {
                                uiState.callbacks.setMicrophonePermission(
                                    SitePermissionState.SITE_PERMISSION_ASK,
                                )
                            },
                        )
                        SettingsRadioOption(
                            label = "許可",
                            selected = uiState.microphonePermission == SitePermissionState.SITE_PERMISSION_ALLOW,
                            onClick = {
                                uiState.callbacks.setMicrophonePermission(
                                    SitePermissionState.SITE_PERMISSION_ALLOW,
                                )
                            },
                        )
                        SettingsRadioOption(
                            label = "ブロック",
                            selected = uiState.microphonePermission == SitePermissionState.SITE_PERMISSION_DENY,
                            onClick = {
                                uiState.callbacks.setMicrophonePermission(
                                    SitePermissionState.SITE_PERMISSION_DENY,
                                )
                            },
                        )
                    }
                }
            }
            if (uiState.geolocationState == null && uiState.microphonePermission == null) {
                Text(
                    text = "このサイトが要求した権限はありません",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            SettingSection(title = "データの削除") {
                TextButton(
                    onClick = {
                        uiState.callbacks.requestClearData(
                            SiteSettingsScreenUiState.ClearDataType.Cookie,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Cookieとサイトデータを削除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(
                    onClick = {
                        uiState.callbacks.requestClearData(
                            SiteSettingsScreenUiState.ClearDataType.Cache,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "キャッシュを削除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.padding(bottom = paddingValues.calculateBottomPadding()))
        }
    }

    // 削除操作の確認ダイアログ（画面の上に重ねて表示する）
    val confirmDialog = uiState.clearDataConfirmDialog
    if (confirmDialog != null) {
        val targetName = when (confirmDialog) {
            SiteSettingsScreenUiState.ClearDataType.Cookie -> "Cookieとサイトデータ"
            SiteSettingsScreenUiState.ClearDataType.Cache -> "キャッシュ"
        }
        val description = when (confirmDialog) {
            SiteSettingsScreenUiState.ClearDataType.Cookie ->
                "「${uiState.host}」のCookieとサイトデータを削除しますか？" +
                    "このサイトからログアウトされます。この操作は取り消せません。"
            SiteSettingsScreenUiState.ClearDataType.Cache ->
                "「${uiState.host}」のキャッシュを削除しますか？この操作は取り消せません。"
        }
        AlertDialog(
            onDismissRequest = uiState.callbacks::dismissClearDataConfirm,
            title = { Text("${targetName}を削除") },
            text = { Text(description) },
            confirmButton = {
                TextButton(onClick = uiState.callbacks::confirmClearData) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = uiState.callbacks::dismissClearDataConfirm) {
                    Text("キャンセル")
                }
            },
        )
    }
}

private val previewCallbacks = object : SiteSettingsScreenUiState.Callbacks {
    override fun setMicrophonePermission(state: SitePermissionState) = Unit
    override fun setGeolocationState(state: SiteGeolocationState) = Unit
    override fun requestClearData(type: SiteSettingsScreenUiState.ClearDataType) = Unit
    override fun confirmClearData() = Unit
    override fun dismissClearDataConfirm() = Unit
    override fun consumeClearDataResultMessage() = Unit
}

@Preview(showBackground = true)
@Composable
private fun SiteSettingsScreenPreview() {
    MaterialTheme {
        SiteSettingsScreen(
            uiState = SiteSettingsScreenUiState(
                callbacks = previewCallbacks,
                host = "www.example.com",
                microphonePermission = SitePermissionState.SITE_PERMISSION_ASK,
                geolocationState = SiteGeolocationState.SITE_GEOLOCATION_MOCK,
                clearDataConfirmDialog = null,
                clearDataResultMessage = null,
            ),
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SiteSettingsScreenGeolocationOnlyPreview() {
    MaterialTheme {
        SiteSettingsScreen(
            uiState = SiteSettingsScreenUiState(
                callbacks = previewCallbacks,
                host = "www.example.com",
                microphonePermission = null,
                geolocationState = SiteGeolocationState.SITE_GEOLOCATION_DENY,
                clearDataConfirmDialog = null,
                clearDataResultMessage = null,
            ),
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SiteSettingsScreenNoRequestedPermissionPreview() {
    MaterialTheme {
        SiteSettingsScreen(
            uiState = SiteSettingsScreenUiState(
                callbacks = previewCallbacks,
                host = "www.example.com",
                microphonePermission = null,
                geolocationState = null,
                clearDataConfirmDialog = null,
                clearDataResultMessage = null,
            ),
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SiteSettingsScreenClearCookieConfirmPreview() {
    MaterialTheme {
        SiteSettingsScreen(
            uiState = SiteSettingsScreenUiState(
                callbacks = previewCallbacks,
                host = "www.example.com",
                microphonePermission = null,
                geolocationState = null,
                clearDataConfirmDialog = SiteSettingsScreenUiState.ClearDataType.Cookie,
                clearDataResultMessage = null,
            ),
            onBack = {},
        )
    }
}
