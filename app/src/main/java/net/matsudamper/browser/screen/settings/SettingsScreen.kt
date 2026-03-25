package net.matsudamper.browser.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.R
import net.matsudamper.browser.data.HomepageType
import net.matsudamper.browser.data.SearchProvider
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.data.TranslationProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    viewModel: SettingsScreenViewModel,
    onOpenExtensions: () -> Unit,
    onOpenNotificationPermissions: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentUiState = uiState ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back_24dp),
                            contentDescription = "戻る",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
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
                            selected = currentUiState.homepageType == HomepageType.HOMEPAGE_GOOGLE,
                            onClick = { currentUiState.callbacks.setHomepageType(HomepageType.HOMEPAGE_GOOGLE) },
                        )
                        SettingsRadioOption(
                            label = "DuckDuckGo",
                            selected = currentUiState.homepageType == HomepageType.HOMEPAGE_DUCKDUCKGO,
                            onClick = { currentUiState.callbacks.setHomepageType(HomepageType.HOMEPAGE_DUCKDUCKGO) },
                        )
                        SettingsRadioOption(
                            label = "カスタム",
                            selected = currentUiState.homepageType == HomepageType.HOMEPAGE_CUSTOM,
                            onClick = { currentUiState.callbacks.setHomepageType(HomepageType.HOMEPAGE_CUSTOM) },
                        )
                    }
                    if (currentUiState.homepageType == HomepageType.HOMEPAGE_CUSTOM) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = currentUiState.customHomepageUrl,
                            onValueChange = currentUiState.callbacks::setCustomHomepageUrl,
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
                            selected = currentUiState.searchProvider == SearchProvider.GOOGLE,
                            onClick = { currentUiState.callbacks.setSearchProvider(SearchProvider.GOOGLE) },
                        )
                        SettingsRadioOption(
                            label = "DuckDuckGo",
                            selected = currentUiState.searchProvider == SearchProvider.DUCKDUCKGO,
                            onClick = { currentUiState.callbacks.setSearchProvider(SearchProvider.DUCKDUCKGO) },
                        )
                        SettingsRadioOption(
                            label = "カスタム",
                            selected = currentUiState.searchProvider == SearchProvider.CUSTOM,
                            onClick = { currentUiState.callbacks.setSearchProvider(SearchProvider.CUSTOM) },
                        )
                    }

                    if (currentUiState.searchProvider == SearchProvider.CUSTOM) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = currentUiState.customSearchUrl,
                            onValueChange = currentUiState.callbacks::setCustomSearchUrl,
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
                            value = currentUiState.enableWebSuggestions,
                            role = Role.Switch,
                            onValueChange = currentUiState.callbacks::setEnableWebSuggestions,
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
                        checked = currentUiState.enableWebSuggestions,
                        onCheckedChange = null,
                    )
                }
            }

            Spacer(Modifier.height(betweenPadding))

            SettingSection(title = "テーマ") {
                Column(Modifier.selectableGroup()) {
                    SettingsRadioOption(
                        label = "システム設定に合わせる",
                        selected = currentUiState.themeMode == ThemeMode.THEME_SYSTEM,
                        onClick = { currentUiState.callbacks.setThemeMode(ThemeMode.THEME_SYSTEM) },
                    )
                    SettingsRadioOption(
                        label = "ライト",
                        selected = currentUiState.themeMode == ThemeMode.THEME_LIGHT,
                        onClick = { currentUiState.callbacks.setThemeMode(ThemeMode.THEME_LIGHT) },
                    )
                    SettingsRadioOption(
                        label = "ダーク",
                        selected = currentUiState.themeMode == ThemeMode.THEME_DARK,
                        onClick = { currentUiState.callbacks.setThemeMode(ThemeMode.THEME_DARK) },
                    )
                }
            }

            Spacer(Modifier.height(betweenPadding))

            SettingSection(title = "翻訳プロバイダー") {
                Column(Modifier.selectableGroup()) {
                    SettingsRadioOption(
                        label = "Gecko",
                        selected = currentUiState.translationProvider == TranslationProvider.TRANSLATION_PROVIDER_GECKO,
                        onClick = {
                            currentUiState.callbacks.setTranslationProvider(
                                TranslationProvider.TRANSLATION_PROVIDER_GECKO,
                            )
                        },
                    )
                    SettingsRadioOption(
                        label = "ローカルAI (Android)",
                        selected = currentUiState.translationProvider == TranslationProvider.TRANSLATION_PROVIDER_LOCAL_AI,
                        onClick = {
                            currentUiState.callbacks.setTranslationProvider(
                                TranslationProvider.TRANSLATION_PROVIDER_LOCAL_AI,
                            )
                        },
                    )
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
                        checked = currentUiState.enableThirdPartyCa,
                        onCheckedChange = currentUiState.callbacks::setEnableThirdPartyCa,
                    )
                }
            }

            Spacer(Modifier.height(betweenPadding))

            SettingSection(title = "ダウンロード") {
                TextButton(
                    onClick = onOpenDownloads,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("ダウンロード管理")
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
            SettingSection(title = "通知") {
                TextButton(
                    onClick = onOpenNotificationPermissions,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("通知を許可したサイト")
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
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

@Composable
private fun SettingsRadioOption(
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
