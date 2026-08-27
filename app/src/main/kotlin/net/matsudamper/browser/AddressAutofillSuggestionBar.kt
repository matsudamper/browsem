package net.matsudamper.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.ui.common.BrowserTheme

/**
 * IME 直上に出す住所・メールの候補。
 * 表示とクリックは [AddressAutofillBarUiState] 経由。
 */
@Stable
internal data class AddressAutofillBarUiState(
    val items: List<Item>,
) {
    @Stable
    data class Item(
        val label: String,
        val supportingText: String,
        val onClick: () -> Unit,
    )
}

@Composable
internal fun AddressAutofillSuggestionBar(
    uiState: AddressAutofillBarUiState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag(AddressAutofillSuggestionBarTestTags.Bar.testTag),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            uiState.items.forEach { item ->
                AddressAutofillSuggestionChip(item = item)
            }
        }
    }
}

@Composable
private fun AddressAutofillSuggestionChip(
    item: AddressAutofillBarUiState.Item,
) {
    Surface(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .testTag(AddressAutofillSuggestionBarTestTags.Option.testTag)
            .clickable(onClick = item.onClick)
            // Gecko の入力フォーカスと IME を奪わない
            .focusProperties { canFocus = false },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.supportingText.isNotEmpty()) {
                Text(
                    text = item.supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

sealed interface AddressAutofillSuggestionBarTestTags {
    val id: String
    val testTag get() = "${AddressAutofillSuggestionBarTestTags::class.java.name}#$id"

    data object Bar : AddressAutofillSuggestionBarTestTags {
        override val id = "address_autofill_suggestion_bar"
    }

    data object Option : AddressAutofillSuggestionBarTestTags {
        override val id = "address_autofill_suggestion_option"
    }
}

@Preview(name = "AddressAutofillSuggestionBar")
@Composable
private fun PreviewAddressAutofillSuggestionBar() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        Box(modifier = Modifier.fillMaxSize()) {
            AddressAutofillSuggestionBar(
                uiState = AddressAutofillBarUiState(
                    items = listOf(
                        AddressAutofillBarUiState.Item(
                            label = "山田 太郎",
                            supportingText = "〒100-0001 東京都千代田区 千代田1-1",
                            onClick = {},
                        ),
                        AddressAutofillBarUiState.Item(
                            label = "佐藤 花子",
                            supportingText = "〒150-0001 東京都渋谷区",
                            onClick = {},
                        ),
                    ),
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }
    }
}

@Preview(name = "AddressAutofillSuggestionBarEmail")
@Composable
private fun PreviewAddressAutofillSuggestionBarEmail() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        Box(modifier = Modifier.fillMaxSize()) {
            AddressAutofillSuggestionBar(
                uiState = AddressAutofillBarUiState(
                    items = listOf(
                        AddressAutofillBarUiState.Item(
                            label = "taro@example.com",
                            supportingText = "山田 太郎",
                            onClick = {},
                        ),
                    ),
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }
    }
}
