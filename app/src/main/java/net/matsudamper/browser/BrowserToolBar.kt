package net.matsudamper.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun BrowserToolBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    showInstallExtensionItem: Boolean,
    onInstallExtension: () -> Unit,
    onOpenSettings: () -> Unit,
    onShare: () -> Unit,
    tabCount: Int,
    onOpenTabs: () -> Unit,
    onRefresh: () -> Unit,
    onHome: () -> Unit,
    onForward: () -> Unit,
    canGoForward: Boolean,
    onFindInPage: () -> Unit,
    isPcMode: Boolean,
    onPcModeToggle: () -> Unit,
    onTranslatePage: () -> Unit,
    toolbarColor: Color?,
    onHorizontalDrag: (Float) -> Unit = {},
    onHorizontalDragEnd: () -> Unit = {},
) {
    var heightCache by remember { mutableIntStateOf(0) }
    val latestOnOpenTabs by rememberUpdatedState(onOpenTabs)

    val latestOnHorizontalDrag by rememberUpdatedState(onHorizontalDrag)
    val latestOnHorizontalDragEnd by rememberUpdatedState(onHorizontalDragEnd)

    val isSystemDarkTheme = isSystemInDarkTheme()
    val resolvedToolbarColor = toolbarColor ?: MaterialTheme.colorScheme.primaryContainer
    val urlBarBackgroundColor: Color
    val toolbarContentColor: Color
    run {
        val isBrightThemeColor = toolbarColor?.luminance()?.let { it >= 0.5f } ?: !isSystemDarkTheme
        urlBarBackgroundColor = if (isBrightThemeColor) {
            Color.Black
        } else {
            Color.LightGray
        }
        toolbarContentColor = if (isBrightThemeColor) Color.White else Color.Black
    }

    val colorSource = if (toolbarColor == null) "default" else "theme"

    Surface(
        color = resolvedToolbarColor,
        contentColor = urlBarBackgroundColor,
        modifier = modifier
            .testTag(TEST_TAG_TOOLBAR)
            .semantics {
                stateDescription = "toolbarColor|$colorSource|${resolvedToolbarColor.toArgbHex()}"
            }
            .pointerInput(isFocused) {
                // 非フォーカス時のみURLバーの水平スワイプでタブ切り替え
                // フォーカス中はテキスト入力を邪魔しないようにする
                if (isFocused) return@pointerInput
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount ->
                        latestOnHorizontalDrag(dragAmount)
                    },
                    onDragEnd = { latestOnHorizontalDragEnd() },
                    onDragCancel = { latestOnHorizontalDragEnd() },
                )
            }
            .pointerInput(isFocused) {
                // 非フォーカス時のみ下スワイプでタブ一覧を開く
                if (isFocused) return@pointerInput
                detectDownSwipe(
                    density = this,
                    onDownSwipe = {
                        latestOnOpenTabs()
                    }
                )
            }
            .onSizeChanged {
                heightCache = it.height.coerceAtLeast(heightCache)
            }
            .defaultMinSize(
                minHeight = with(LocalDensity.current) { heightCache.toDp() }
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .height(IntrinsicSize.Min)
                    .weight(1f)
                    .padding(4.dp),
                contentColor = toolbarContentColor,
                color = urlBarBackgroundColor,
                shape = CircleShape,
            ) {
                Row(
                    modifier = Modifier
                        .padding(
                            start = 8.dp,
                            end = 4.dp,
                            top = 4.dp,
                            bottom = 4.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UrlTextInput(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(
                                state = rememberScrollState(),
                                enabled = isFocused,
                            ),
                        value = value,
                        onValueChange = onValueChange,
                        onSubmit = onSubmit,
                        onFocusChanged = onFocusChanged,
                        textColor = LocalContentColor.current,
                    )

                    if (isFocused) {
                        CompositionLocalProvider(
                            LocalMinimumInteractiveComponentSize provides 0.dp
                        ) {
                            Icon(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(1f)
                                    .clickable(
                                        indication = ripple(),
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick = { onValueChange("") }
                                    ),
                                painter = painterResource(R.drawable.close_24dp),
                                contentDescription = "クリア",
                            )
                        }
                    }
                }
            }

            if (!isFocused) {
                IconButton(
                    onClick = onOpenTabs,
                ) {
                    Text(
                        text = "$tabCount",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                var visibleMenu by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { visibleMenu = !visibleMenu }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert_24dp),
                        contentDescription = "Menu",
                    )
                    ToolbarMenu(
                        visibleMenu = visibleMenu,
                        onDismissRequest = { visibleMenu = false },
                        onRefresh = onRefresh,
                        onHome = onHome,
                        onForward = onForward,
                        canGoForward = canGoForward,
                        isPcMode = isPcMode,
                        onPcModeToggle = onPcModeToggle,
                        showInstallExtensionItem = showInstallExtensionItem,
                        onInstallExtension = onInstallExtension,
                        onTranslatePage = onTranslatePage,
                        onShare = onShare,
                        onFindInPage = onFindInPage,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        }
    }
}

@Composable
fun UrlTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    val currentOnFocusChanged by rememberUpdatedState(onFocusChanged)
    val textColor by rememberUpdatedState(textColor)

    // 一度AndroidViewを経由しないとBitwardenが認識しない
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ComposeView(context).apply {
                setContent {
                    BasicTextField(
                        value = currentValue,
                        onValueChange = { currentOnValueChange(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("url_bar")
                            .onFocusChanged { currentOnFocusChanged(it.hasFocus) }
                            .semantics {
                                contentDescription = "Address bar"
                                contentType = ContentType("url")
                                contentDataType = ContentDataType.Text
                            },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge
                            .merge(
                                color = textColor,
                                textAlign = TextAlign.Start,
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions.Default.copy(
                            imeAction = ImeAction.Go,
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = { currentOnSubmit(currentValue) },
                            onDone = { currentOnSubmit(currentValue) },
                            onSearch = { currentOnSubmit(currentValue) },
                        ),
                    )
                }
            }
        },
    )
}

internal const val TEST_TAG_TOOLBAR = "browser_toolbar"

private fun Color.toArgbHex(): String {
    val raw = toArgb().toUInt().toString(16).padStart(8, '0')
    return "#${raw.uppercase()}"
}

@Composable
private fun ToolbarMenu(
    visibleMenu: Boolean,
    onDismissRequest: () -> Unit,
    onRefresh: () -> Unit,
    onHome: () -> Unit,
    onForward: () -> Unit,
    canGoForward: Boolean,
    isPcMode: Boolean,
    onPcModeToggle: () -> Unit,
    showInstallExtensionItem: Boolean,
    onInstallExtension: () -> Unit,
    onTranslatePage: () -> Unit,
    onShare: () -> Unit,
    onFindInPage: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    DropdownMenu(
        expanded = visibleMenu,
        onDismissRequest = { onDismissRequest() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = {
                        onDismissRequest()
                        onForward()
                    },
                    enabled = canGoForward,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_forward_24dp),
                        contentDescription = "進む",
                    )
                }
                Text(
                    text = "進む",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = {
                        onDismissRequest()
                        onHome()
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_home_24dp),
                        contentDescription = "ホーム",
                    )
                }
                Text(
                    text = "ホーム",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = {
                        onDismissRequest()
                        onRefresh()
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh_24dp),
                        contentDescription = "更新",
                    )
                }
                Text(
                    text = "更新",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(text = "PCページ") },
            leadingIcon = {
                Checkbox(
                    checked = isPcMode,
                    onCheckedChange = null,
                )
            },
            onClick = { onPcModeToggle() },
        )
        if (showInstallExtensionItem) {
            DropdownMenuItem(
                text = {
                    Text(text = "拡張機能をインストール")
                },
                onClick = {
                    onDismissRequest()
                    onInstallExtension()
                },
            )
        }
        DropdownMenuItem(
            text = {
                Text(text = "翻訳")
            },
            onClick = {
                onDismissRequest()
                onTranslatePage()
            },
        )
        DropdownMenuItem(
            text = {
                Text(text = "共有")
            },
            onClick = {
                onDismissRequest()
                onShare()
            },
        )
        DropdownMenuItem(
            text = {
                Text(text = "ページ内検索")
            },
            onClick = {
                onDismissRequest()
                onFindInPage()
            },
        )
        DropdownMenuItem(
            text = {
                Text(text = "設定")
            },
            onClick = {
                onDismissRequest()
                onOpenSettings()
            },
        )
    }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun Preview() {
    BrowserTheme(themeMode = net.matsudamper.browser.data.ThemeMode.THEME_SYSTEM) {
        Column {
            for (isFocused in listOf(true, false)) {
                BrowserToolBar(
                    value = "https://google.com",
                    onValueChange = {},
                    onSubmit = {},
                    isFocused = isFocused,
                    onFocusChanged = {},
                    showInstallExtensionItem = true,
                    onInstallExtension = {},
                    onOpenSettings = {},
                    onShare = {},
                    tabCount = 2,
                    onOpenTabs = {},
                    isPcMode = false,
                    onPcModeToggle = {},
                    onFindInPage = {},
                    toolbarColor = null,
                    onRefresh = {},
                    onHome = {},
                    onForward = {},
                    canGoForward = false,
                    onTranslatePage = {},
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDownSwipe(
    density: Density,
    onDownSwipe: () -> Unit,
) {
    val triggerDistance = with(density) { 56.dp.toPx() }
    var totalDrag = 0f
    detectVerticalDragGestures(
        onDragStart = {
            totalDrag = 0f
        },
        onVerticalDrag = { _, dragAmount ->
            if (dragAmount > 0f) {
                totalDrag += dragAmount
            } else {
                totalDrag = 0f
            }
        },
        onDragEnd = {
            if (totalDrag >= triggerDistance) {
                onDownSwipe()
            }
            totalDrag = 0f
        },
        onDragCancel = {
            totalDrag = 0f
        },
    )
}
