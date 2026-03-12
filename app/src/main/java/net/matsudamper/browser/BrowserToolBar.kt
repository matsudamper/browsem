package net.matsudamper.browser

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

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
    showTabActions: Boolean = true,
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
    var visibleMenu by remember { mutableStateOf(false) }
    BrowserToolbar(
        modifier = modifier,
        isFocused = isFocused,
        gestureState = if (showTabActions) {
            BrowserToolBarGestureState(
                onHorizontalDrag = onHorizontalDrag,
                onHorizontalDragEnd = onHorizontalDragEnd,
                onOpenTabs = onOpenTabs
            )
        } else {
            null
        },
        toolbarColor = toolbarColor,
        onOpenTabs = onOpenTabs,
        tabCount = tabCount,
        showTabButton = showTabActions,
        urlInputState = UrlInputState(
            value = value,
            onValueChange = onValueChange,
            onSubmit = onSubmit,
            onFocusChanged = onFocusChanged,
            enableSuggest = true,
            scrollEnabled = isFocused,
        ),
        updateVisibleMenu = {
            visibleMenu = it
        },
        toolbarMenu = {
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
    )
}

internal class BrowserToolBarGestureState(
    onHorizontalDrag: (Float) -> Unit,
    onHorizontalDragEnd: () -> Unit,
    onOpenTabs: () -> Unit,
) {
    var isFocused by mutableStateOf(false)

    val modifier = Modifier
        .pointerInput(isFocused) {
            // 非フォーカス時のみURLバーの水平スワイプでタブ切り替え
            // フォーカス中はテキスト入力を邪魔しないようにする
            if (isFocused) return@pointerInput
            detectHorizontalDragGestures(
                onHorizontalDrag = { _, dragAmount ->
                    onHorizontalDrag(dragAmount)
                },
                onDragEnd = { onHorizontalDragEnd() },
                onDragCancel = { onHorizontalDragEnd() },
            )
        }
        .pointerInput(isFocused) {
            // 非フォーカス時のみ下スワイプでタブ一覧を開く
            if (isFocused) return@pointerInput
            detectDownSwipe(
                density = this,
                onDownSwipe = {
                    onOpenTabs()
                }
            )
        }
}

data class UrlInputState(
    val enableSuggest: Boolean,
    val scrollEnabled: Boolean,
    val onValueChange: (String) -> Unit,
    val onSubmit: (String) -> Unit,
    val onFocusChanged: (Boolean) -> Unit,
    val value: String,
)

@Composable
internal fun BrowserToolbar(
    isFocused: Boolean,
    gestureState: BrowserToolBarGestureState?,
    urlInputState: UrlInputState,
    toolbarColor: Color?,
    updateVisibleMenu: (Boolean) -> Unit,
    onOpenTabs: () -> Unit,
    tabCount: Int,
    showTabButton: Boolean = true,
    toolbarMenu: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var heightCache by remember { mutableIntStateOf(0) }

    val toolbarColors = resolveBrowserToolbarColors(
        toolbarColor = toolbarColor,
        defaultToolbarColor = MaterialTheme.colorScheme.primaryContainer,
        isSystemDarkTheme = isSystemInDarkTheme(),
    )

    Surface(
        color = toolbarColors.resolvedToolbarColor,
        contentColor = toolbarColors.urlBarBackgroundColor,
        modifier = modifier
            .testTag(TEST_TAG_TOOLBAR)
            .semantics {
                stateDescription = "toolbarColor|${toolbarColors.colorSource}|${toolbarColors.resolvedToolbarColor.toArgbHex()}"
            }
            .then(gestureState?.modifier ?: Modifier)
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
                contentColor = toolbarColors.toolbarContentColor,
                color = toolbarColors.urlBarBackgroundColor,
                shape = CircleShape,
            ) {
                Row(
                    modifier = Modifier
                        .padding(
                            end = 4.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UrlTextInput(
                        modifier = Modifier
                            .weight(1f),
                        enableSuggest = urlInputState.enableSuggest,
                        paddingValues = PaddingValues(
                            start = 8.dp,
                            top = 4.dp,
                            bottom = 4.dp
                        ),
                        scrollEnabled = isFocused,
                        value = urlInputState.value,
                        onValueChange = urlInputState.onValueChange,
                        onSubmit = urlInputState.onSubmit,
                        onFocusChanged = urlInputState.onFocusChanged,
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
                                        onClick = { urlInputState.onValueChange("") }
                                    ),
                                painter = painterResource(R.drawable.close_24dp),
                                contentDescription = "クリア",
                            )
                        }
                    }
                }
            }

            if (!isFocused) {
                if (showTabButton) {
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
                }
                IconButton(
                    onClick = { updateVisibleMenu(true) }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert_24dp),
                        contentDescription = "Menu",
                    )
                    toolbarMenu()
                }
            }
        }
    }
}

internal const val TEST_TAG_TOOLBAR = "browser_toolbar"

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
