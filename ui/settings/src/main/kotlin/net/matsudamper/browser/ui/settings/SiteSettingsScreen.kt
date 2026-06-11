package net.matsudamper.browser.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
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
            ),
            onBack = {},
        )
    }
}
