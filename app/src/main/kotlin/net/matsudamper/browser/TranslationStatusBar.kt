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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.Locale
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.resources.R as ResourcesR
import net.matsudamper.browser.ui.common.BrowserTheme

internal enum class TranslationState {
    Idle,
    Loading,
    ScanningPage,
    DetectingLanguage,
    PreparingModel,
    Translating,
    Translated,
    Error,
}

internal val TranslationState.isInProgress: Boolean
    get() = when (this) {
        TranslationState.Loading,
        TranslationState.ScanningPage,
        TranslationState.DetectingLanguage,
        TranslationState.PreparingModel,
        TranslationState.Translating,
        -> true

        TranslationState.Idle,
        TranslationState.Translated,
        TranslationState.Error,
        -> false
    }

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
        TranslationState.ScanningPage,
        TranslationState.DetectingLanguage,
        TranslationState.PreparingModel,
        TranslationState.Translating,
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
            if (state.isInProgress) {
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

                    TranslationState.Loading,
                    TranslationState.ScanningPage,
                    TranslationState.DetectingLanguage,
                    TranslationState.PreparingModel,
                    TranslationState.Translating,
                    -> {
                        Text(
                            text = translationProgressLabel(state),
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

internal fun translationProgressLabel(state: TranslationState): String = when (state) {
    TranslationState.Loading -> "翻訳を開始中..."

    TranslationState.ScanningPage -> "ページを解析中..."

    TranslationState.DetectingLanguage -> "翻訳言語を確認中..."

    TranslationState.PreparingModel -> "ML翻訳モデルを準備中..."

    TranslationState.Translating -> "翻訳中..."

    TranslationState.Idle,
    TranslationState.Translated,
    TranslationState.Error,
    -> ""
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

@Preview(name = "ML翻訳モデル準備中", widthDp = 360)
@Composable
private fun PreviewTranslationStatusBarPreparingModel() {
    BrowserTheme(themeMode = ThemeMode.THEME_LIGHT) {
        TranslationStatusBar(
            state = TranslationState.PreparingModel,
            onRevert = {},
            onDismissError = {},
        )
    }
}
