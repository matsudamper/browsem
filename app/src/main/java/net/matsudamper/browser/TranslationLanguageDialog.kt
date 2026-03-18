package net.matsudamper.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.Locale
import net.matsudamper.browser.data.ThemeMode

/**
 * 翻訳元・翻訳先の言語を選択するダイアログ。
 * 選択肢は検出済み言語＋英語＋日本語（重複除去）。
 */
@Composable
internal fun TranslationLanguageDialog(
    detectedLanguage: String?,
    selectedFromLanguage: String?,
    selectedToLanguage: String,
    onFromLanguageSelected: (String?) -> Unit,
    onToLanguageSelected: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 選択肢：検出済み言語＋英語＋日本語（重複除去）
    val fromLanguageOptions: List<String?> = remember(detectedLanguage) {
        val langs = mutableListOf<String?>()
        if (detectedLanguage != null && detectedLanguage != "en" && detectedLanguage != "ja") {
            langs.add(detectedLanguage)
        }
        langs.add("en")
        langs.add("ja")
        langs
    }
    val toLanguageOptions: List<String> = remember(detectedLanguage) {
        val langs = mutableListOf<String>()
        if (detectedLanguage != null && detectedLanguage != "en" && detectedLanguage != "ja") {
            langs.add(detectedLanguage)
        }
        langs.add("en")
        langs.add("ja")
        langs
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("翻訳言語の設定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LanguageDropdown(
                    label = "翻訳元",
                    options = fromLanguageOptions,
                    selectedLanguage = selectedFromLanguage,
                    onSelected = onFromLanguageSelected,
                )
                LanguageDropdown(
                    label = "翻訳先",
                    options = toLanguageOptions,
                    selectedLanguage = selectedToLanguage,
                    onSelected = { it?.let(onToLanguageSelected) },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("翻訳")
            }
        },
    )
}

/** 言語タグからロケール表示名を返す。nullの場合は「自動検出」。 */
private fun languageDisplayName(tag: String?): String {
    if (tag == null) return "自動検出"
    val locale = Locale.forLanguageTag(tag)
    val name = locale.getDisplayLanguage(Locale.JAPANESE)
    return if (name.isBlank()) tag else name
}

/** 言語選択プルダウン（ExposedDropdownMenuBox）。String? 対応版。 */
@Composable
private fun LanguageDropdown(
    label: String,
    options: List<String?>,
    selectedLanguage: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = languageDisplayName(selectedLanguage),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(languageDisplayName(lang)) },
                    onClick = {
                        onSelected(lang)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewTranslationLanguageDialog() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        TranslationLanguageDialog(
            detectedLanguage = "en",
            selectedFromLanguage = "en",
            selectedToLanguage = "ja",
            onFromLanguageSelected = {},
            onToLanguageSelected = {},
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "検出済み言語あり")
@Composable
private fun PreviewWithDetectedLanguage() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        TranslationLanguageDialog(
            detectedLanguage = "zh",
            selectedFromLanguage = "zh",
            selectedToLanguage = "ja",
            onFromLanguageSelected = {},
            onToLanguageSelected = {},
            onConfirm = {},
            onDismiss = {},
        )
    }
}
