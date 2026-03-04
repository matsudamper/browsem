package net.matsudamper.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class TranslationState { Idle, Loading, Translated, Error }

@Composable
internal fun TranslationStatusBar(
    state: TranslationState,
    onRevert: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == TranslationState.Idle) return

    val backgroundColor = when (state) {
        TranslationState.Loading,
        TranslationState.Translated -> MaterialTheme.colorScheme.secondaryContainer

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
                Text(
                    text = when (state) {
                        TranslationState.Loading -> "翻訳中..."
                        TranslationState.Translated -> "翻訳済み"
                        TranslationState.Error -> "翻訳に失敗しました"
                        TranslationState.Idle -> ""
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state) {
                        TranslationState.Error -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
                when (state) {
                    TranslationState.Translated -> {
                        TextButton(onClick = onRevert) {
                            Text(text = "元に戻す")
                        }
                    }

                    TranslationState.Error -> {
                        IconButton(onClick = onDismissError) {
                            Icon(
                                imageVector = Icons.Default.Close,
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
