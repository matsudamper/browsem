package net.matsudamper.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.resources.R as ResourcesR
import net.matsudamper.browser.ui.common.BrowserTheme

internal enum class FindInPageBarKind {
    Text,
    Semantic,
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FindInPageBar(
    query: String,
    matchCurrent: Int,
    matchTotal: Int,
    kind: FindInPageBarKind,
    isRegex: Boolean,
    isSearching: Boolean,
    searchProgressMessage: String?,
    searchProgressDetail: String?,
    queryError: String?,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
    onToggleRegex: () -> Unit,
    onSearchCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // ステータスバー領域の高さ分だけコンテンツを下に押し出す。
                // Surface の背景色はステータスバー領域まで延びて塗りつぶされる。
                modifier = Modifier
                    .windowInsetsPadding(
                        WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                if (kind == FindInPageBarKind.Text) {
                    Box(
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(
                            modifier = Modifier,
                            onClick = onToggleRegex,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isRegex) {
                                    MaterialTheme.colorScheme.inversePrimary
                                } else {
                                    Color.Unspecified
                                },
                            ),
                        ) {
                            Icon(
                                modifier = Modifier
                                    .size(24.dp),
                                painter = painterResource(ResourcesR.drawable.ic_regurar_expression),
                                contentDescription = "正規表現",
                            )
                        }
                    }
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .focusRequester(focusRequester)
                        .testTag(FindInPageBarTestTags.SearchInput.testTag),
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (kind == FindInPageBarKind.Semantic) {
                                onSearchCommit()
                            } else {
                                onNext()
                            }
                        },
                    ),
                    decorationBox = { innerTextField ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    text = when (kind) {
                                        FindInPageBarKind.Semantic -> "質問を入力し、Enter で検索"

                                        FindInPageBarKind.Text ->
                                            if (isRegex) "正規表現で検索..." else "ページ内を検索..."
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                if (kind == FindInPageBarKind.Semantic && isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .size(20.dp)
                            .testTag(FindInPageBarTestTags.SearchProgress.testTag),
                        strokeWidth = 2.dp,
                    )
                } else if (query.isNotEmpty() && queryError == null) {
                    Text(
                        text = "$matchCurrent/$matchTotal",
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(
                    onClick = onPrevious,
                    enabled = query.isNotEmpty() && queryError == null && !isSearching,
                ) {
                    Icon(
                        painter = painterResource(ResourcesR.drawable.ic_keyboard_arrow_up_24dp),
                        contentDescription = "前へ",
                    )
                }
                IconButton(
                    onClick = onNext,
                    enabled = query.isNotEmpty() && queryError == null && !isSearching,
                ) {
                    Icon(
                        painter = painterResource(ResourcesR.drawable.ic_keyboard_arrow_down_24dp),
                        contentDescription = "次へ",
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(ResourcesR.drawable.close_24dp),
                        contentDescription = "閉じる",
                    )
                }
            }
            if (kind == FindInPageBarKind.Semantic && isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (searchProgressMessage != null) {
                    Text(
                        text = searchProgressMessage,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .testTag(FindInPageBarTestTags.SearchProgressMessage.testTag),
                    )
                }
                if (searchProgressDetail != null) {
                    Text(
                        text = searchProgressDetail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }
            if (queryError != null) {
                Text(
                    text = queryError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }
    }
}

sealed interface FindInPageBarTestTags {
    val id: String
    val testTag get() = "${FindInPageBarTestTags::class.java.name}#$id"

    object SearchInput : FindInPageBarTestTags {
        override val id = "search_input"
    }

    object SearchProgress : FindInPageBarTestTags {
        override val id = "search_progress"
    }

    object SearchProgressMessage : FindInPageBarTestTags {
        override val id = "search_progress_message"
    }
}

@Preview(name = "SemanticFindInPageSearching", widthDp = 412)
@Composable
private fun PreviewSemanticFindInPageSearching() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        FindInPageBar(
            query = "このページの主題は？",
            matchCurrent = 0,
            matchTotal = 0,
            kind = FindInPageBarKind.Semantic,
            isRegex = false,
            isSearching = true,
            searchProgressMessage = "Google API を呼び出しています",
            searchProgressDetail = "候補 24 件・API 呼び出し 1/1",
            queryError = null,
            onQueryChange = {},
            onNext = {},
            onPrevious = {},
            onClose = {},
            onToggleRegex = {},
            onSearchCommit = {},
        )
    }
}
