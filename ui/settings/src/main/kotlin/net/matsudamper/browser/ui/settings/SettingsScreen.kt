package net.matsudamper.browser.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.data.HomepageType
import net.matsudamper.browser.data.SearchProvider
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.resources.R as ResourcesR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsScreenUiState,
    onOpenExtensions: () -> Unit,
    onOpenHistory: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("設定") },
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
            val betweenPadding = 12.dp
            SettingSection(title = "ホームページ") {
                Column {
                    Column(Modifier.selectableGroup()) {
                        SettingsRadioOption(
                            label = "Google",
                            selected = uiState.homepageType == HomepageType.HOMEPAGE_GOOGLE,
                            onClick = { uiState.callbacks.setHomepageType(HomepageType.HOMEPAGE_GOOGLE) },
                        )
                        SettingsRadioOption(
                            label = "DuckDuckGo",
                            selected = uiState.homepageType == HomepageType.HOMEPAGE_DUCKDUCKGO,
                            onClick = { uiState.callbacks.setHomepageType(HomepageType.HOMEPAGE_DUCKDUCKGO) },
                        )
                        SettingsRadioOption(
                            label = "カスタム",
                            selected = uiState.homepageType == HomepageType.HOMEPAGE_CUSTOM,
                            onClick = { uiState.callbacks.setHomepageType(HomepageType.HOMEPAGE_CUSTOM) },
                        )
                    }
                    if (uiState.homepageType == HomepageType.HOMEPAGE_CUSTOM) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = uiState.customHomepageUrl,
                            onValueChange = uiState.callbacks::setCustomHomepageUrl,
                            label = { Text("ホームページ URL") },
                            placeholder = { Text("https://example.com") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(betweenPadding))

            SettingSection(title = "検索プロバイダー") {
                Column {
                    Column(Modifier.selectableGroup()) {
                        SettingsRadioOption(
                            label = "Google",
                            selected = uiState.searchProvider == SearchProvider.GOOGLE,
                            onClick = { uiState.callbacks.setSearchProvider(SearchProvider.GOOGLE) },
                        )
                        SettingsRadioOption(
                            label = "DuckDuckGo",
                            selected = uiState.searchProvider == SearchProvider.DUCKDUCKGO,
                            onClick = { uiState.callbacks.setSearchProvider(SearchProvider.DUCKDUCKGO) },
                        )
                        SettingsRadioOption(
                            label = "カスタム",
                            selected = uiState.searchProvider == SearchProvider.CUSTOM,
                            onClick = { uiState.callbacks.setSearchProvider(SearchProvider.CUSTOM) },
                        )
                    }

                    if (uiState.searchProvider == SearchProvider.CUSTOM) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = uiState.customSearchUrl,
                            onValueChange = uiState.callbacks::setCustomSearchUrl,
                            label = { Text("検索 URL") },
                            placeholder = { Text("https://example.com/search?q=%s") },
                            supportingText = { Text("%s に検索ワードが入ります") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(betweenPadding))

            SettingSection(title = "検索候補") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = uiState.enableWebSuggestions,
                            role = Role.Switch,
                            onValueChange = uiState.callbacks::setEnableWebSuggestions,
                        )
                        .padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Webサジェストを有効化",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "入力中のキーワードを検索エンジンへ送信して候補を表示します",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = uiState.enableWebSuggestions,
                        onCheckedChange = null,
                    )
                }
            }

            Spacer(Modifier.height(betweenPadding))

            SettingSection(title = "テーマ") {
                Column(Modifier.selectableGroup()) {
                    SettingsRadioOption(
                        label = "システム設定に合わせる",
                        selected = uiState.themeMode == ThemeMode.THEME_SYSTEM,
                        onClick = { uiState.callbacks.setThemeMode(ThemeMode.THEME_SYSTEM) },
                    )
                    SettingsRadioOption(
                        label = "ライト",
                        selected = uiState.themeMode == ThemeMode.THEME_LIGHT,
                        onClick = { uiState.callbacks.setThemeMode(ThemeMode.THEME_LIGHT) },
                    )
                    SettingsRadioOption(
                        label = "ダーク",
                        selected = uiState.themeMode == ThemeMode.THEME_DARK,
                        onClick = { uiState.callbacks.setThemeMode(ThemeMode.THEME_DARK) },
                    )
                }
            }

            Spacer(Modifier.height(betweenPadding))

            SettingSection(title = "翻訳プロバイダー") {
                Column(Modifier.selectableGroup()) {
                    SettingsRadioOption(
                        label = "Gecko",
                        selected = uiState.translationProvider == TranslationProvider.TRANSLATION_PROVIDER_GECKO,
                        onClick = {
                            uiState.callbacks.setTranslationProvider(
                                TranslationProvider.TRANSLATION_PROVIDER_GECKO,
                            )
                        },
                    )
                    SettingsRadioOption(
                        label = "ローカルAI (Android)",
                        selected = uiState.translationProvider == TranslationProvider.TRANSLATION_PROVIDER_LOCAL_AI,
                        onClick = {
                            uiState.callbacks.setTranslationProvider(
                                TranslationProvider.TRANSLATION_PROVIDER_LOCAL_AI,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(betweenPadding))

            SettingSection(title = "位置情報") {
                Column {
                    Text(
                        text = "モック位置情報の座標",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "サイトごとの設定が「モック位置情報」のサイトへ、実際の位置ではなくこの座標を返します",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = uiState.mockLocationInput,
                        onValueChange = uiState.callbacks::setMockLocationInput,
                        label = { Text("緯度,経度") },
                        placeholder = { Text("35.685175,139.752797") },
                        supportingText = {
                            val error = uiState.mockLocationInputError
                            if (error != null) {
                                Text(error, color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("カンマ区切りで「緯度,経度」を入力してください")
                            }
                        },
                        isError = uiState.mockLocationInputError != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = uiState.callbacks::openMockLocationOnMap,
                        enabled = uiState.mockLocationInputError == null && uiState.mockLocationInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("地図で確認")
                    }
                }
            }

            Spacer(Modifier.height(betweenPadding))

            SettingSection(title = "セキュリティ") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = "サードパーティーCAを有効化",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = uiState.enableThirdPartyCa,
                        onCheckedChange = uiState.callbacks::setEnableThirdPartyCa,
                    )
                }
            }

            Spacer(Modifier.height(betweenPadding))

            SettingSection(title = "バックアップ") {
                Column {
                    Text(
                        text = "設定・タブ・タブグループ・Cookie・ログイン情報・履歴 (Gecko 側)・" +
                            "サイト権限・サイト別設定を zip ファイルにエクスポート/インポートします。" +
                            "キャッシュ・ダウンロード記録・閲覧履歴 (アプリ側 DB) は対象外です。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = uiState.callbacks::requestBackupExport,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("エクスポート")
                        }
                        OutlinedButton(
                            onClick = uiState.callbacks::requestBackupImport,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("インポート")
                        }
                    }
                }
            }

            Spacer(Modifier.height(betweenPadding))

            SettingSection(title = "拡張機能") {
                TextButton(
                    onClick = onOpenExtensions,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("インストール済み拡張機能を管理")
                }
            }

            Spacer(Modifier.height(betweenPadding))

            SettingSection(title = "履歴") {
                TextButton(
                    onClick = onOpenHistory,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("閲覧履歴を検索")
                }
            }

            Spacer(Modifier.height(betweenPadding))
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.padding(bottom = paddingValues.calculateBottomPadding()))
        }
    }

    // バックアップ操作の確認ダイアログ（設定画面の上に重ねて表示する）
    val confirmDialog = uiState.backupConfirmDialog
    if (confirmDialog != null) {
        val isImport = confirmDialog == SettingsScreenUiState.BackupConfirmType.Import
        AlertDialog(
            onDismissRequest = uiState.callbacks::dismissBackupConfirm,
            title = { Text(if (isImport) "インポートを開始しますか？" else "エクスポートを開始しますか？") },
            text = {
                Text(
                    "ブラウザのセッションおよび進行中のダウンロードを停止します。" +
                        "この操作中は他の操作を行えません。",
                )
            },
            confirmButton = {
                Button(onClick = uiState.callbacks::confirmBackup) {
                    Text("開始")
                }
            },
            dismissButton = {
                TextButton(onClick = uiState.callbacks::dismissBackupConfirm) {
                    Text("キャンセル")
                }
            },
        )
    }
}

@Composable
internal fun SettingSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

// 設定項目が縦に長いため、全体が見えるように高さを広げて Preview する
@Preview(showBackground = true, heightDp = 2400)
@Composable
private fun SettingsScreenPreview() {
    MaterialTheme {
        SettingsScreen(
            uiState = SettingsScreenUiState(
                callbacks = object : SettingsScreenUiState.Callbacks {
                    override fun setHomepageType(type: HomepageType) = Unit
                    override fun setCustomHomepageUrl(url: String) = Unit
                    override fun setSearchProvider(provider: SearchProvider) = Unit
                    override fun setCustomSearchUrl(url: String) = Unit
                    override fun setThemeMode(mode: ThemeMode) = Unit
                    override fun setTranslationProvider(provider: TranslationProvider) = Unit
                    override fun setEnableThirdPartyCa(enabled: Boolean) = Unit
                    override fun setEnableWebSuggestions(enabled: Boolean) = Unit
                    override fun setMockLocationInput(input: String) = Unit
                    override fun openMockLocationOnMap() = Unit
                    override fun requestBackupExport() = Unit
                    override fun requestBackupImport() = Unit
                    override fun confirmBackup() = Unit
                    override fun dismissBackupConfirm() = Unit
                },
                homepageType = HomepageType.HOMEPAGE_GOOGLE,
                customHomepageUrl = "",
                searchProvider = SearchProvider.GOOGLE,
                customSearchUrl = "",
                themeMode = ThemeMode.THEME_SYSTEM,
                translationProvider = TranslationProvider.TRANSLATION_PROVIDER_GECKO,
                enableThirdPartyCa = false,
                enableWebSuggestions = false,
                mockLocationInput = "35.685175,139.752797",
                mockLocationInputError = null,
                backupConfirmDialog = null,
            ),
            onOpenExtensions = {},
            onOpenHistory = {},
            onBack = {},
        )
    }
}

@Composable
internal fun SettingsRadioOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
