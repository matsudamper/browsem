package net.matsudamper.browser

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.data.BrowserSettings
import net.matsudamper.browser.data.HomepageType
import net.matsudamper.browser.data.SearchProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    settings: BrowserSettings,
    onSettingsChange: (BrowserSettings) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // ── ホームページ ──────────────────────────────
            Text(
                text = "ホームページ",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            Column(Modifier.selectableGroup()) {
                HomepageOption(
                    label = "Google",
                    selected = settings.homepageType == HomepageType.HOMEPAGE_GOOGLE,
                    onClick = {
                        onSettingsChange(
                            settings.toBuilder()
                                .setHomepageType(HomepageType.HOMEPAGE_GOOGLE)
                                .build(),
                        )
                    },
                )
                HomepageOption(
                    label = "DuckDuckGo",
                    selected = settings.homepageType == HomepageType.HOMEPAGE_DUCKDUCKGO,
                    onClick = {
                        onSettingsChange(
                            settings.toBuilder()
                                .setHomepageType(HomepageType.HOMEPAGE_DUCKDUCKGO)
                                .build(),
                        )
                    },
                )
                HomepageOption(
                    label = "カスタム",
                    selected = settings.homepageType == HomepageType.HOMEPAGE_CUSTOM,
                    onClick = {
                        onSettingsChange(
                            settings.toBuilder()
                                .setHomepageType(HomepageType.HOMEPAGE_CUSTOM)
                                .build(),
                        )
                    },
                )
            }

            if (settings.homepageType == HomepageType.HOMEPAGE_CUSTOM) {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = settings.customHomepageUrl,
                    onValueChange = { url ->
                        onSettingsChange(
                            settings.toBuilder()
                                .setCustomHomepageUrl(url)
                                .build(),
                        )
                    },
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

            Spacer(Modifier.height(24.dp))

            // ── 検索プロバイダー ─────────────────────────
            Text(
                text = "検索プロバイダー",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            Column(Modifier.selectableGroup()) {
                SearchProviderOption(
                    label = "Google",
                    selected = settings.searchProvider == SearchProvider.GOOGLE,
                    onClick = {
                        onSettingsChange(
                            settings.toBuilder()
                                .setSearchProvider(SearchProvider.GOOGLE)
                                .build(),
                        )
                    },
                )
                SearchProviderOption(
                    label = "DuckDuckGo",
                    selected = settings.searchProvider == SearchProvider.DUCKDUCKGO,
                    onClick = {
                        onSettingsChange(
                            settings.toBuilder()
                                .setSearchProvider(SearchProvider.DUCKDUCKGO)
                                .build(),
                        )
                    },
                )
                SearchProviderOption(
                    label = "カスタム",
                    selected = settings.searchProvider == SearchProvider.CUSTOM,
                    onClick = {
                        onSettingsChange(
                            settings.toBuilder()
                                .setSearchProvider(SearchProvider.CUSTOM)
                                .build(),
                        )
                    },
                )
            }

            if (settings.searchProvider == SearchProvider.CUSTOM) {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = settings.customSearchUrl,
                    onValueChange = { url ->
                        onSettingsChange(
                            settings.toBuilder()
                                .setCustomSearchUrl(url)
                                .build(),
                        )
                    },
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

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HomepageOption(
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

@Composable
private fun SearchProviderOption(
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
