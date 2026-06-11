package net.matsudamper.browser.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
                SettingSection(title = "TLS証明書") {
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

            Spacer(Modifier.padding(bottom = paddingValues.calculateBottomPadding()))
        }
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
                tlsCertificate = SiteSettingsScreenUiState.TlsCertificate.Available(
                    subjectCommonName = "www.example.com",
                    issuer = "Example CA",
                    validFrom = "2026/01/01 00:00",
                    validUntil = "2027/01/01 00:00",
                    sha256Fingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:" +
                        "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
                ),
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
                tlsCertificate = SiteSettingsScreenUiState.TlsCertificate.Insecure,
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
                tlsCertificate = null,
            ),
            onBack = {},
        )
    }
}
