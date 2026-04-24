package net.matsudamper.browser

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.matsudamper.browser.ui.common.BrowserTheme
import net.matsudamper.browser.ui.common.resolveBrowserToolbarColors
import net.matsudamper.browser.ui.common.toArgbHex
import net.matsudamper.browser.resources.R as ResourcesR

@Composable
internal fun BrowserToolBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    showInstallExtensionItem: Boolean,
    onInstallExtension: () -> Unit,
    onOpenSettings: () -> Unit,
    onShare: () -> Unit,
    tabCount: Int?,
    onOpenTabs: () -> Unit,
    onRefresh: () -> Unit,
    onSuperRefresh: () -> Unit,
    onHome: () -> Unit,
    onForward: () -> Unit,
    canGoForward: Boolean,
    onBack: () -> Unit,
    canGoBack: Boolean,
    onLongPressHistory: () -> Unit,
    onFindInPage: () -> Unit,
    isPcMode: Boolean,
    onPcModeToggle: () -> Unit,
    onTranslatePage: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    pageZoomPercent: Int,
    onPageZoomIn: () -> Unit,
    onPageZoomOut: () -> Unit,
    onResetPageZoom: () -> Unit,
    toolbarColor: Color?,
    modifier: Modifier = Modifier,
    showTabActions: Boolean = true,
    onHorizontalDrag: (Float) -> Unit = {},
    onHorizontalDragEnd: () -> Unit = {},
) {
    var visibleMenu by remember { mutableStateOf(false) }
    BrowserToolbar(
        modifier = modifier
            .testTag(BrowserToolbarTestTags.Toolbar.testTag),
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
                onSuperRefresh = onSuperRefresh,
                onHome = onHome,
                onForward = onForward,
                canGoForward = canGoForward,
                onBack = onBack,
                canGoBack = canGoBack,
                onLongPressHistory = onLongPressHistory,
                isPcMode = isPcMode,
                onPcModeToggle = onPcModeToggle,
                showInstallExtensionItem = showInstallExtensionItem,
                onInstallExtension = onInstallExtension,
                onTranslatePage = onTranslatePage,
                onShare = onShare,
                onFindInPage = onFindInPage,
                onOpenSettings = onOpenSettings,
                onAddToHomeScreen = onAddToHomeScreen,
                pageZoomPercent = pageZoomPercent,
                onPageZoomIn = onPageZoomIn,
                onPageZoomOut = onPageZoomOut,
                onResetPageZoom = onResetPageZoom,
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
@OptIn(ExperimentalLayoutApi::class)
internal fun BrowserToolbar(
    isFocused: Boolean,
    gestureState: BrowserToolBarGestureState?,
    urlInputState: UrlInputState,
    toolbarColor: Color?,
    updateVisibleMenu: (Boolean) -> Unit,
    onOpenTabs: () -> Unit,
    tabCount: Int?,
    modifier: Modifier = Modifier,
    showTabButton: Boolean = true,
    toolbarMenu: @Composable () -> Unit,
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
            .semantics {
                stateDescription = "toolbarColor|${toolbarColors.colorSource}|${toolbarColors.resolvedToolbarColor.toArgbHex()}"
            }
            .then(gestureState?.modifier ?: Modifier),
    ) {
        Row(
            // ステータスバーが一時的に非表示扱いになっても通常時の領域分だけコンテンツを下に押し出す。
            // Surface の背景色はステータスバー領域まで延びて塗りつぶされる。
            // サイズキャッシュは windowInsetsPadding の内側（Row 自身）で取ることで、
            // フローティング／マルチウィンドウで status bar のインセットが 0 になっても
            // 旧フルスクリーン時の「URL バー + status bar」分の高さで固定されないようにする。
            // IntrinsicSize.Min で Row 高さを子の最小 intrinsic 高さに合わせる（URL バーの自然高さ）。
            modifier = Modifier
                .windowInsetsPadding(
                    WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top)
                )
                .onSizeChanged {
                    heightCache = it.height.coerceAtLeast(heightCache)
                }
                .defaultMinSize(
                    minHeight = with(LocalDensity.current) { heightCache.toDp() }
                )
                .height(IntrinsicSize.Min),
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
                            .testTag(BrowserToolbarTestTags.Url(urlInputState.value).testTag)
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
                                painter = painterResource(ResourcesR.drawable.close_24dp),
                                contentDescription = "クリア",
                            )
                        }
                    }
                }
            }

            if (!isFocused) {
                if (showTabButton) {
                    TabCountButton(
                        modifier = Modifier
                            .semantics(mergeDescendants = true) {
                                contentDescription = "タブ件数、${tabCount?.toString()?.plus("件").orEmpty()}"
                                role = Role.Button
                            }
                            .fillMaxHeight()
                            .padding(4.dp)
                            .padding(vertical = 4.dp),
                        tabCount = tabCount,
                        onOpenTabs = onOpenTabs,
                    )
                }
                IconButton(
                    modifier = Modifier.testTag(BrowserToolbarTestTags.MenuButton.testTag),
                    onClick = { updateVisibleMenu(true) },
                ) {
                    Icon(
                        painter = painterResource(ResourcesR.drawable.ic_more_vert_24dp),
                        contentDescription = "Menu",
                    )
                    toolbarMenu()
                }
            }
        }
    }
}

/**
 * Row(IntrinsicSize.Min) から降りてくる maxHeight を一辺に採用する正方形レイアウト。
 * 幅方向の intrinsic を height に固定し、子 BasicText のテキスト幅が Row 高さへ伝播して URL バー高さが伸びる問題を遮断する。
 */
private val TabCountSquareMeasurePolicy = object : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val side = when {
            constraints.hasBoundedHeight -> constraints.maxHeight
            else -> constraints.minHeight
        }
        val placeables = measurables.map { it.measure(Constraints.fixed(side, side)) }
        return layout(side, side) {
            placeables.forEach { it.place(0, 0) }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = if (height == Constraints.Infinity) {
        measurables.firstOrNull()?.minIntrinsicHeight(Constraints.Infinity) ?: 0
    } else {
        height
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = if (height == Constraints.Infinity) {
        measurables.firstOrNull()?.maxIntrinsicHeight(Constraints.Infinity) ?: 0
    } else {
        height
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = measurables.firstOrNull()?.minIntrinsicHeight(width) ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = measurables.firstOrNull()?.maxIntrinsicHeight(width) ?: 0
}

@Composable
private fun TabCountButton(
    tabCount: Int?,
    onOpenTabs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(
                indication = ripple(),
                interactionSource = remember { MutableInteractionSource() },
                onClick = onOpenTabs,
            )
            .testTag(BrowserToolbarTestTags.OpenTabsButton.testTag),
        measurePolicy = TabCountSquareMeasurePolicy,
        content = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (tabCount != null) {
                    BasicText(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        text = "$tabCount",
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 6.sp,
                            maxFontSize = 16.sp,
                            stepSize = 1.sp,
                        ),
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = LocalContentColor.current,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        },
    )
}

sealed class BrowserToolbarTestTags(val id: String) {
    val testTag: String = "${BrowserToolbarTestTags::class.java.name}#$id"

    class Url(value: String) : BrowserToolbarTestTags(
        id = "url#$value",
    )

    object Toolbar : BrowserToolbarTestTags(
        id = "toolbar",
    )

    object OpenTabsButton : BrowserToolbarTestTags(
        id = "open_tabs_button",
    )

    object MenuButton : BrowserToolbarTestTags(
        id = "MenuButton",
    )
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
                    onAddToHomeScreen = {},
                    pageZoomPercent = 100,
                    onPageZoomIn = {},
                    onPageZoomOut = {},
                    onResetPageZoom = {},
                    toolbarColor = null,
                    onRefresh = {},
                    onSuperRefresh = {},
                    onHome = {},
                    onForward = {},
                    canGoForward = false,
                    onBack = {},
                    canGoBack = false,
                    onLongPressHistory = {},
                    onTranslatePage = {},
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Preview(name = "TabCountVariants")
@Composable
private fun PreviewTabCountVariants() {
    BrowserTheme(themeMode = net.matsudamper.browser.data.ThemeMode.THEME_SYSTEM) {
        Column {
            for (tabCount in listOf(null, 2, 99, 999, 9999, 10000)) {
                BrowserToolBar(
                    value = "https://google.com",
                    onValueChange = {},
                    onSubmit = {},
                    isFocused = false,
                    onFocusChanged = {},
                    showInstallExtensionItem = true,
                    onInstallExtension = {},
                    onOpenSettings = {},
                    onShare = {},
                    tabCount = tabCount,
                    onOpenTabs = {},
                    isPcMode = false,
                    onPcModeToggle = {},
                    onFindInPage = {},
                    onAddToHomeScreen = {},
                    pageZoomPercent = 100,
                    onPageZoomIn = {},
                    onPageZoomOut = {},
                    onResetPageZoom = {},
                    toolbarColor = null,
                    onRefresh = {},
                    onSuperRefresh = {},
                    onHome = {},
                    onForward = {},
                    canGoForward = false,
                    onBack = {},
                    canGoBack = false,
                    onLongPressHistory = {},
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
