package net.matsudamper.browser

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 * IME 直上に出す住所・名前・メールの候補。
 * 表示とクリックは [AddressAutofillBarUiState] 経由。
 */
@Stable
internal data class AddressAutofillBarUiState(
    val items: List<Item>,
) {
    @Stable
    data class Item(
        val label: String,
        val kind: AddressAutofillSuggestionKind,
        val onClick: () -> Unit,
    )
}

private val SuggestionButtonMaxWidth = 280.dp
private val SuggestionButtonTextMaxWidth = 256.dp

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
                AddressAutofillSuggestionButton(item = item)
            }
        }
    }
}

@Composable
private fun AddressAutofillSuggestionButton(
    item: AddressAutofillBarUiState.Item,
) {
    OutlinedButton(
        onClick = item.onClick,
        modifier = Modifier
            .widthIn(max = SuggestionButtonMaxWidth)
            .testTag(item.kind.optionTestTag)
            // Gecko の入力フォーカスと IME を奪わない
            .focusProperties { canFocus = false },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = SuggestionButtonTextMaxWidth),
        )
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

    data object NameOption : AddressAutofillSuggestionBarTestTags {
        override val id = "address_autofill_suggestion_option_name"
    }

    data object AddressOption : AddressAutofillSuggestionBarTestTags {
        override val id = "address_autofill_suggestion_option_address"
    }

    data object EmailOption : AddressAutofillSuggestionBarTestTags {
        override val id = "address_autofill_suggestion_option_email"
    }
}

internal val AddressAutofillSuggestionKind.optionTestTag: String
    get() = when (this) {
        AddressAutofillSuggestionKind.Name -> AddressAutofillSuggestionBarTestTags.NameOption.testTag
        AddressAutofillSuggestionKind.Address -> AddressAutofillSuggestionBarTestTags.AddressOption.testTag
        AddressAutofillSuggestionKind.Email -> AddressAutofillSuggestionBarTestTags.EmailOption.testTag
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
                            kind = AddressAutofillSuggestionKind.Name,
                            onClick = {},
                        ),
                        AddressAutofillBarUiState.Item(
                            label = "佐藤 花子",
                            kind = AddressAutofillSuggestionKind.Name,
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

@Preview(name = "AddressAutofillSuggestionBarAddress")
@Composable
private fun PreviewAddressAutofillSuggestionBarAddress() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        Box(modifier = Modifier.fillMaxSize()) {
            AddressAutofillSuggestionBar(
                uiState = AddressAutofillBarUiState(
                    items = listOf(
                        AddressAutofillBarUiState.Item(
                            label = "〒100-0001 東京都千代田区 千代田1-1",
                            kind = AddressAutofillSuggestionKind.Address,
                            onClick = {},
                        ),
                        AddressAutofillBarUiState.Item(
                            label = "〒150-0001 東京都渋谷区神宮前1-1-1",
                            kind = AddressAutofillSuggestionKind.Address,
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
                            kind = AddressAutofillSuggestionKind.Email,
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
