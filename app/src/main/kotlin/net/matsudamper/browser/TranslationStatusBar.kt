package net.matsudamper.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import net.matsudamper.browser.resources.R as ResourcesR

internal enum class TranslationState { Idle, Loading, Translated, Error }

@Composable
internal fun TranslationStatusBar(
    state: TranslationState,
    onRevert: () -> Unit,
    onDismissError: () -> Unit,
    fromLanguage: String? = null,
    toLanguage: String? = null,
    /** 翻訳元の選択肢（言語タグ一覧）。nullなら言語変更UIを表示しない。 */
    fromLanguageOptions: List<String>? = null,
    /** 翻訳先の選択肢（言語タグ一覧）。nullなら言語変更UIを表示しない。 */
    toLanguageOptions: List<String>? = null,
    onFromLanguageSelected: (String) -> Unit = {},
    onToLanguageSelected: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (state == TranslationState.Idle) return

    val backgroundColor = when (state) {
        TranslationState.Loading,
        TranslationState.Translated,
        -> MaterialTheme.colorScheme.secondaryContainer

        TranslationState.Error -> MaterialTheme.colorScheme.errorContainer
        TranslationState.Idle -> return
    }

    Surface(
        color = backgroundColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            if (state == TranslationState.Loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                when (state) {
                    TranslationState.Translated -> {
                        Text(
                            text = "翻訳済み: ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        // 翻訳元言語ドロップダウン
                        LanguageDropdownButton(
                            languageTag = fromLanguage,
                            options = fromLanguageOptions,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            onSelected = onFromLanguageSelected,
                        )
                        Text(
                            text = " → ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        // 翻訳先言語ドロップダウン
                        LanguageDropdownButton(
                            languageTag = toLanguage,
                            options = toLanguageOptions,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            onSelected = onToLanguageSelected,
                        )
                    }

                    TranslationState.Loading -> {
                        Text(
                            text = "翻訳中...",
                            modifier = Modifier.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }

                    TranslationState.Error -> {
                        Text(
                            text = "翻訳に失敗しました",
                            modifier = Modifier.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }

                    TranslationState.Idle -> {}
                }

                // 右端のアクションボタン
                when (state) {
                    TranslationState.Translated -> {
                        TextButton(onClick = onRevert) {
                            Text(text = "元に戻す")
                        }
                    }

                    TranslationState.Error -> {
                        IconButton(onClick = onDismissError) {
                            Icon(
                                painter = painterResource(ResourcesR.drawable.close_24dp),
                                contentDescription = "閉じる",
                            )
                        }
                    }

                    else -> {}
                }
            }
        }
    }
}

/** 言語タグを表示名で示すTextButton。クリックでDropdownMenuを展開する。 */
@Composable
private fun LanguageDropdownButton(
    languageTag: String?,
    options: List<String>?,
    color: androidx.compose.ui.graphics.Color,
    onSelected: (String) -> Unit,
) {
    val displayName = languageDisplayName(languageTag)
    if (options.isNullOrEmpty()) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        return
    }

    var expanded by remember { mutableStateOf(false) }
    TextButton(
        onClick = { expanded = true },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(languageDisplayName(lang)) },
                    onClick = {
                        expanded = false
                        onSelected(lang)
                    },
                )
            }
        }
    }
}

private fun languageDisplayName(tag: String?): String {
    if (tag == null) return "不明"
    val locale = Locale.forLanguageTag(tag)
    val name = locale.getDisplayLanguage(Locale.JAPANESE)
    return if (name.isBlank()) tag else name
}
