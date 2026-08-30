package net.matsudamper.browser.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.data.SiteGeolocationState
import net.matsudamper.browser.data.SitePermissionState
import net.matsudamper.browser.resources.R as ResourcesR

sealed interface SiteSettingsScreenTestTags {
    val id: String

    val testTag get() = "${SiteSettingsScreenTestTags::class.java.name}#$id"

    object Root : SiteSettingsScreenTestTags { override val id = "root" }
}

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
        modifier = modifier.testTag(SiteSettingsScreenTestTags.Root.testTag),
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

            if (uiState.tlsCertificate != null) {
                CollapsibleSettingSection(title = "TLS証明書") {
                    when (val certificate = uiState.tlsCertificate) {
                        is SiteSettingsScreenUiState.TlsCertificate.Available -> {
                            CertificateInfoRow(label = "発行先", value = certificate.subjectCommonName)
                            CertificateInfoRow(label = "発行者", value = certificate.issuer)
                            CertificateInfoRow(label = "有効期間の開始", value = certificate.validFrom)
                            CertificateInfoRow(label = "有効期間の終了", value = certificate.validUntil)
                            CertificateInfoRow(
                                label = "SHA-256 フィンガープリント",
                                value = certificate.sha256Fingerprint,
                            )
                        }

                        SiteSettingsScreenUiState.TlsCertificate.Insecure -> {
                            Text(
                                text = "この接続は保護されていません",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 権限は証明書・データ削除と同じく1つのコンテナにまとめ、
            // 要求が無い場合もタイトル付きセクション内に空メッセージを表示して見た目を揃える
            SettingSection(title = "サイトが要求した権限") {
                val hasGeolocation = uiState.geolocationState != null
                val hasMicrophone = uiState.microphonePermission != null
                val hasAutoplay = uiState.autoplayPermission != null
                if (!hasGeolocation && !hasMicrophone && !hasAutoplay) {
                    Text(
                        text = "このサイトが要求した権限はありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 一度でも要求された権限だけをサブ項目として表示する
                    if (hasGeolocation) {
                        PermissionGroup(
                            title = "位置情報",
                            iconRes = R.drawable.ic_location_on,
                        ) {
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
                    }
                    if (hasMicrophone) {
                        if (hasGeolocation) {
                            Spacer(Modifier.height(12.dp))
                        }
                        PermissionGroup(
                            title = "マイク",
                            iconRes = R.drawable.ic_mic,
                        ) {
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
                    if (hasAutoplay) {
                        if (hasGeolocation || hasMicrophone) {
                            Spacer(Modifier.height(12.dp))
                        }
                        PermissionGroup(
                            title = "音声の自動再生",
                            iconRes = R.drawable.ic_volume_up,
                        ) {
                            Column(Modifier.selectableGroup()) {
                                SettingsRadioOption(
                                    label = "確認する",
                                    selected = uiState.autoplayPermission == SitePermissionState.SITE_PERMISSION_ASK,
                                    onClick = {
                                        uiState.callbacks.setAutoplayPermission(
                                            SitePermissionState.SITE_PERMISSION_ASK,
                                        )
                                    },
                                )
                                SettingsRadioOption(
                                    label = "許可",
                                    selected = uiState.autoplayPermission == SitePermissionState.SITE_PERMISSION_ALLOW,
                                    onClick = {
                                        uiState.callbacks.setAutoplayPermission(
                                            SitePermissionState.SITE_PERMISSION_ALLOW,
                                        )
                                    },
                                )
                                SettingsRadioOption(
                                    label = "ブロック",
                                    selected = uiState.autoplayPermission == SitePermissionState.SITE_PERMISSION_DENY,
                                    onClick = {
                                        uiState.callbacks.setAutoplayPermission(
                                            SitePermissionState.SITE_PERMISSION_DENY,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (uiState.savedFormInputPathCount > 0) {
                SettingSection(title = "保存したフォーム入力") {
                    TextButton(
                        onClick = uiState.callbacks::openSavedFormInputs,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${uiState.savedFormInputPathCount} 件のパスを管理")
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

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

/** 権限セクション内の個別の権限（位置情報・マイクなど）をサブタイトル付きで表示する */
@Composable
private fun PermissionGroup(
    title: String,
    @DrawableRes iconRes: Int,
    content: @Composable () -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        content()
    }
}

/** 証明書のラベルと値の1行分を表示する */
@Composable
private fun CertificateInfoRow(
    label: String,
    value: String,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private val previewCallbacks = object : SiteSettingsScreenUiState.Callbacks {
    override fun setMicrophonePermission(state: SitePermissionState) = Unit
    override fun setGeolocationState(state: SiteGeolocationState) = Unit
    override fun setAutoplayPermission(state: SitePermissionState) = Unit
    override fun requestClearData(type: SiteSettingsScreenUiState.ClearDataType) = Unit
    override fun confirmClearData() = Unit
    override fun dismissClearDataConfirm() = Unit
    override fun consumeClearDataResultMessage() = Unit
    override fun openSavedFormInputs() = Unit
}

// 証明書・位置情報・マイク・音声の自動再生・データ削除をすべて含むため、見切れないよう縦を広げて Preview する
@Preview(showBackground = true)
@Composable
private fun SiteSettingsScreenWithFormInputPreview() {
    MaterialTheme {
        SiteSettingsScreen(
            uiState = SiteSettingsScreenUiState(
                callbacks = previewCallbacks,
                host = "www.example.com",
                microphonePermission = null,
                geolocationState = null,
                autoplayPermission = null,
                tlsCertificate = null,
                clearDataConfirmDialog = null,
                clearDataResultMessage = null,
                savedFormInputPathCount = 2,
            ),
            onBack = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun SiteSettingsScreenPreview() {
    MaterialTheme {
        SiteSettingsScreen(
            uiState = SiteSettingsScreenUiState(
                callbacks = previewCallbacks,
                host = "www.example.com",
                microphonePermission = SitePermissionState.SITE_PERMISSION_ASK,
                geolocationState = SiteGeolocationState.SITE_GEOLOCATION_MOCK,
                autoplayPermission = SitePermissionState.SITE_PERMISSION_DENY,
                tlsCertificate = SiteSettingsScreenUiState.TlsCertificate.Available(
                    subjectCommonName = "www.example.com",
                    issuer = "Example CA",
                    validFrom = "2026/01/01 00:00",
                    validUntil = "2027/01/01 00:00",
                    sha256Fingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:" +
                        "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
                ),
                clearDataConfirmDialog = null,
                clearDataResultMessage = null,
                savedFormInputPathCount = 0,
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
                autoplayPermission = null,
                tlsCertificate = SiteSettingsScreenUiState.TlsCertificate.Insecure,
                clearDataConfirmDialog = null,
                clearDataResultMessage = null,
                savedFormInputPathCount = 0,
            ),
            onBack = {},
        )
    }
}

/** 音声の自動再生だけが要求された場合の表示を確認する */
@Preview(showBackground = true)
@Composable
private fun SiteSettingsScreenAutoplayOnlyPreview() {
    MaterialTheme {
        SiteSettingsScreen(
            uiState = SiteSettingsScreenUiState(
                callbacks = previewCallbacks,
                host = "www.example.com",
                microphonePermission = null,
                geolocationState = null,
                autoplayPermission = SitePermissionState.SITE_PERMISSION_ASK,
                tlsCertificate = SiteSettingsScreenUiState.TlsCertificate.Insecure,
                clearDataConfirmDialog = null,
                clearDataResultMessage = null,
                savedFormInputPathCount = 0,
            ),
            onBack = {},
        )
    }
}

/** セクション内のコンテンツが短い場合でもコンテナが画面幅いっぱいに広がることを確認する */
@Preview(showBackground = true)
@Composable
private fun SiteSettingsScreenShortContentPreview() {
    MaterialTheme {
        SiteSettingsScreen(
            uiState = SiteSettingsScreenUiState(
                callbacks = previewCallbacks,
                host = "a.test",
                microphonePermission = null,
                geolocationState = null,
                autoplayPermission = null,
                tlsCertificate = SiteSettingsScreenUiState.TlsCertificate.Insecure,
                clearDataConfirmDialog = null,
                clearDataResultMessage = null,
                savedFormInputPathCount = 0,
            ),
            onBack = {},
        )
    }
}

/** 横向きでもコンテナが画面幅いっぱいに広がることを確認する */
@Preview(showBackground = true, widthDp = 800, heightDp = 360)
@Composable
private fun SiteSettingsScreenLandscapePreview() {
    MaterialTheme {
        SiteSettingsScreen(
            uiState = SiteSettingsScreenUiState(
                callbacks = previewCallbacks,
                host = "a.test",
                microphonePermission = null,
                geolocationState = null,
                autoplayPermission = null,
                tlsCertificate = SiteSettingsScreenUiState.TlsCertificate.Insecure,
                clearDataConfirmDialog = null,
                clearDataResultMessage = null,
                savedFormInputPathCount = 0,
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
                autoplayPermission = null,
                tlsCertificate = null,
                clearDataConfirmDialog = null,
                clearDataResultMessage = null,
                savedFormInputPathCount = 0,
            ),
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "TLS証明書展開")
@Composable
private fun SiteSettingsScreenTlsCertificateExpandedPreview() {
    MaterialTheme {
        CollapsibleSettingSection(
            title = "TLS証明書",
            initiallyExpanded = true,
        ) {
            CertificateInfoRow(label = "発行先", value = "www.example.com")
            CertificateInfoRow(label = "発行者", value = "Example CA")
            CertificateInfoRow(label = "有効期間の開始", value = "2026/01/01 00:00")
            CertificateInfoRow(label = "有効期間の終了", value = "2027/01/01 00:00")
            CertificateInfoRow(
                label = "SHA-256 フィンガープリント",
                value = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:" +
                    "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
            )
        }
    }
}

@Preview(showBackground = true, name = "TLS証明書折りたたみ")
@Composable
private fun SiteSettingsScreenTlsCertificateCollapsedPreview() {
    MaterialTheme {
        CollapsibleSettingSection(title = "TLS証明書") {
            CertificateInfoRow(label = "発行先", value = "www.example.com")
            CertificateInfoRow(label = "発行者", value = "Example CA")
        }
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
                autoplayPermission = null,
                tlsCertificate = null,
                clearDataConfirmDialog = SiteSettingsScreenUiState.ClearDataType.Cookie,
                clearDataResultMessage = null,
                savedFormInputPathCount = 0,
            ),
            onBack = {},
        )
    }
}
