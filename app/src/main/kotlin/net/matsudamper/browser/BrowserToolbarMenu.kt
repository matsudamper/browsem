package net.matsudamper.browser

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.data.ProfileIcon
import net.matsudamper.browser.data.ProfileId
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.resources.R as ResourcesR
import net.matsudamper.browser.ui.common.BrowserTheme
import net.matsudamper.browser.ui.tabs.MoveTabToProfileDialog
import net.matsudamper.browser.ui.tabs.ProfileIconBadge
import net.matsudamper.browser.ui.tabs.ProfileManagementDialog
import net.matsudamper.browser.ui.tabs.ProfileSwitcherUiState

private val PreviewProfileSwitcherUiStateForMenu = ProfileSwitcherUiState(
    activeProfileIcon = ProfileIcon.PERSON,
    profiles = listOf(),
    callbacks = object : ProfileSwitcherUiState.Callbacks {
        override fun onAddProfile() = Unit
    },
)

/** DropdownMenu の上下マージン。Material3 の MenuVerticalMargin と同値 */
private val ToolbarMenuVerticalMargin = 48.dp

private val ToolbarMenuScrollbarWidth = 3.dp
private val ToolbarMenuScrollbarColor = Color(0xFFBDBDBD)
private val ToolbarMenuScrollbarMinThumbHeight = 24.dp

/**
 * スクロール可能なときだけ右端に薄いグレーのスクロールバーを常時表示する。
 * verticalScroll より前にチェーンすること。
 */
private fun Modifier.toolbarMenuScrollbar(scrollState: ScrollState): Modifier = drawWithContent {
    drawContent()
    val indicator = scrollState.scrollIndicatorState ?: return@drawWithContent
    val contentSize = indicator.contentSize
    val viewportSize = indicator.viewportSize
    if (contentSize <= viewportSize || viewportSize <= 0) {
        return@drawWithContent
    }
    val scrollbarWidthPx = ToolbarMenuScrollbarWidth.toPx()
    val minThumbHeightPx = ToolbarMenuScrollbarMinThumbHeight.toPx()
    val maxThumbHeightPx = viewportSize.toFloat()
    val effectiveMinThumbHeightPx = minOf(minThumbHeightPx, maxThumbHeightPx)
    val thumbHeight = (viewportSize.toFloat() / contentSize * viewportSize)
        .coerceIn(effectiveMinThumbHeightPx, maxThumbHeightPx)
    val maxScroll = (contentSize - viewportSize).coerceAtLeast(1)
    val trackHeight = viewportSize - thumbHeight
    val thumbOffset = indicator.scrollOffset.toFloat() / maxScroll * trackHeight
    drawRoundRect(
        color = ToolbarMenuScrollbarColor,
        topLeft = Offset(x = size.width - scrollbarWidthPx, y = thumbOffset),
        size = Size(width = scrollbarWidthPx, height = thumbHeight),
        cornerRadius = CornerRadius(scrollbarWidthPx / 2f),
    )
}

/**
 * キーボード表示中にツールバーメニューが IME に隠れないよう、
 * アンカー下端から IME 上端までの高さをメニューの高さ上限とする。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun rememberToolbarMenuMaxHeight(menuAnchorBottomPx: Int): Dp {
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    return with(density) {
        val marginPx = ToolbarMenuVerticalMargin.roundToPx()
        val imeTopPx = windowHeightPx - imeBottomPx
        val availablePx = (imeTopPx - menuAnchorBottomPx - marginPx).coerceAtLeast(0)
        availablePx.toDp()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ToolbarMenu(
    visibleMenu: Boolean,
    menuAnchorBottomPx: Int,
    onDismissRequest: () -> Unit,
    onRefresh: () -> Unit,
    onSuperRefresh: () -> Unit,
    onHome: () -> Unit,
    onForward: () -> Unit,
    canGoForward: Boolean,
    onBack: () -> Unit,
    canGoBack: Boolean,
    onLongPressHistory: () -> Unit,
    isPcMode: Boolean,
    onPcModeToggle: () -> Unit,
    showInstallExtensionItem: Boolean,
    onInstallExtension: () -> Unit,
    onTranslatePage: () -> Unit,
    onShare: () -> Unit,
    onFindInPage: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    pageZoomPercent: Int,
    onPageZoomIn: () -> Unit,
    onPageZoomOut: () -> Unit,
    onResetPageZoom: () -> Unit,
    isPageLoading: Boolean,
    onStopLoading: () -> Unit,
    extensionActions: List<WebExtensionActionController.ActionUiState>,
    extensionActionScrollState: ScrollState?,
    onExtensionActionMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onExtensionActionMoveEnd: () -> Unit,
    onExtensionActionMoveCancel: () -> Unit,
    showOpenSettings: Boolean,
    showAddToHomeScreen: Boolean,
    showHome: Boolean,
    onOpenInBrowser: (() -> Unit)?,
    onOpenSiteSettings: (() -> Unit)?,
    onOpenDownloads: (() -> Unit)?,
    onOpenDevTools: (() -> Unit)?,
    profileSwitcher: ProfileSwitcherUiState?,
    onMoveTabToProfile: ((ProfileId) -> Unit)?,
) {
    val menuScrollState = rememberScrollState()
    val menuMaxHeight = rememberToolbarMenuMaxHeight(menuAnchorBottomPx)
    DropdownMenu(
        expanded = visibleMenu,
        onDismissRequest = { onDismissRequest() },
        modifier = Modifier
            .heightIn(max = menuMaxHeight)
            .toolbarMenuScrollbar(menuScrollState),
        scrollState = menuScrollState,
    ) {
        ToolbarMenuContent(
            onDismissRequest = onDismissRequest,
            onRefresh = onRefresh,
            onSuperRefresh = onSuperRefresh,
            isPageLoading = isPageLoading,
            onStopLoading = onStopLoading,
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
            extensionActions = extensionActions,
            extensionActionScrollState = extensionActionScrollState,
            onExtensionActionMove = onExtensionActionMove,
            onExtensionActionMoveEnd = onExtensionActionMoveEnd,
            onExtensionActionMoveCancel = onExtensionActionMoveCancel,
            showOpenSettings = showOpenSettings,
            showAddToHomeScreen = showAddToHomeScreen,
            showHome = showHome,
            onOpenInBrowser = onOpenInBrowser,
            onOpenSiteSettings = onOpenSiteSettings,
            onOpenDownloads = onOpenDownloads,
            onOpenDevTools = onOpenDevTools,
            profileSwitcher = profileSwitcher,
            onMoveTabToProfile = onMoveTabToProfile,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolbarMenuContent(
    onDismissRequest: () -> Unit,
    onRefresh: () -> Unit,
    onSuperRefresh: () -> Unit,
    onHome: () -> Unit,
    onForward: () -> Unit,
    canGoForward: Boolean,
    onBack: () -> Unit,
    canGoBack: Boolean,
    onLongPressHistory: () -> Unit,
    isPcMode: Boolean,
    onPcModeToggle: () -> Unit,
    showInstallExtensionItem: Boolean,
    onInstallExtension: () -> Unit,
    onTranslatePage: () -> Unit,
    onShare: () -> Unit,
    onFindInPage: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    pageZoomPercent: Int,
    onPageZoomIn: () -> Unit,
    onPageZoomOut: () -> Unit,
    onResetPageZoom: () -> Unit,
    extensionActions: List<WebExtensionActionController.ActionUiState>,
    extensionActionScrollState: ScrollState?,
    onExtensionActionMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onExtensionActionMoveEnd: () -> Unit,
    onExtensionActionMoveCancel: () -> Unit,
    showOpenSettings: Boolean,
    showAddToHomeScreen: Boolean,
    showHome: Boolean,
    onOpenInBrowser: (() -> Unit)?,
    onOpenSiteSettings: (() -> Unit)?,
    onOpenDownloads: (() -> Unit)?,
    isPageLoading: Boolean,
    onStopLoading: () -> Unit,
    onOpenDevTools: (() -> Unit)?,
    profileSwitcher: ProfileSwitcherUiState?,
    onMoveTabToProfile: ((ProfileId) -> Unit)?,
) {
    var isProfileDialogVisible by remember { mutableStateOf(false) }
    var isMoveDialogVisible by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .combinedClickable(
                            enabled = canGoBack,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false),
                            role = Role.Button,
                            onLongClick = {
                                onDismissRequest()
                                onLongPressHistory()
                            },
                            onClick = {
                                onDismissRequest()
                                onBack()
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_arrow_back_24dp),
                            contentDescription = null,
                            tint = if (canGoBack) {
                                LocalContentColor.current
                            } else {
                                LocalContentColor.current.copy(alpha = 0.38f)
                            },
                        )
                    }
                }
                MenuColumnLabel(text = "戻る")
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .combinedClickable(
                            enabled = canGoForward,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false),
                            role = Role.Button,
                            onLongClick = {
                                onDismissRequest()
                                onLongPressHistory()
                            },
                            onClick = {
                                onDismissRequest()
                                onForward()
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_arrow_forward_24dp),
                            contentDescription = null,
                            tint = if (canGoForward) {
                                LocalContentColor.current
                            } else {
                                LocalContentColor.current.copy(alpha = 0.38f)
                            },
                        )
                    }
                }
                MenuColumnLabel(text = "進む")
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .testTag(BrowserToolbarMenuTestTags.RefreshButton.testTag)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false),
                            role = Role.Button,
                            onLongClick = if (isPageLoading) {
                                null
                            } else {
                                {
                                    onDismissRequest()
                                    onSuperRefresh()
                                }
                            },
                            onClick = {
                                onDismissRequest()
                                if (isPageLoading) {
                                    onStopLoading()
                                } else {
                                    onRefresh()
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isPageLoading) {
                                    ResourcesR.drawable.close_24dp
                                } else {
                                    ResourcesR.drawable.ic_refresh_24dp
                                },
                            ),
                            contentDescription = null,
                        )
                    }
                }
                MenuColumnLabel(text = if (isPageLoading) "停止" else "更新")
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (showHome) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        modifier = Modifier
                            .testTag(BrowserToolbarMenuTestTags.HomeButton.testTag),
                        onClick = {
                            onDismissRequest()
                            onHome()
                        },
                    ) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_home_24dp),
                            contentDescription = null,
                        )
                    }
                    MenuColumnLabel(text = "ホーム")
                }
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    modifier = Modifier
                        .testTag(BrowserToolbarMenuTestTags.ShareButton.testTag),
                    onClick = {
                        onDismissRequest()
                        onShare()
                    },
                ) {
                    Icon(
                        painter = painterResource(ResourcesR.drawable.ic_share_24dp),
                        contentDescription = null,
                    )
                }
                MenuColumnLabel(text = "共有")
            }
            if (onOpenSiteSettings != null) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        modifier = Modifier
                            .testTag(BrowserToolbarMenuTestTags.SiteSettingsButton.testTag),
                        onClick = {
                            onDismissRequest()
                            onOpenSiteSettings()
                        },
                    ) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_settings_24dp),
                            contentDescription = null,
                        )
                    }
                    MenuColumnLabel(text = "サイトの設定")
                }
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ズーム",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .testTag(BrowserToolbarMenuTestTags.ZoomLabel.testTag),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPageZoomOut) {
                    Icon(
                        painter = painterResource(ResourcesR.drawable.ic_remove_24dp),
                        contentDescription = "縮小",
                    )
                }
                TextButton(
                    onClick = onResetPageZoom,
                    modifier = Modifier
                        .widthIn(min = 64.dp)
                        .testTag(BrowserToolbarMenuTestTags.ZoomPercentButton.testTag),
                ) {
                    Text(
                        text = "$pageZoomPercent%",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(onClick = onPageZoomIn) {
                    Icon(
                        painter = painterResource(ResourcesR.drawable.ic_add_24dp),
                        contentDescription = "拡大",
                    )
                }
            }
        }
        if (profileSwitcher != null && onMoveTabToProfile != null) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "プロファイル",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        modifier = Modifier
                            .testTag(BrowserToolbarMenuTestTags.ProfileSwitchButton.testTag)
                            .semantics { contentDescription = "プロファイルを管理" },
                        onClick = { isProfileDialogVisible = true },
                    ) {
                        ProfileIconBadge(icon = profileSwitcher.activeProfileIcon, size = 28.dp, isEmphasized = false)
                    }
                    IconButton(
                        modifier = Modifier.testTag(BrowserToolbarMenuTestTags.MoveTabToProfileButton.testTag),
                        onClick = { isMoveDialogVisible = true },
                    ) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_drive_file_move_24dp),
                            contentDescription = "タブを別のプロファイルへ移動",
                        )
                    }
                }
            }
        }
        if (extensionActions.isNotEmpty() && extensionActionScrollState != null) {
            HorizontalDivider()
            ExtensionActionRow(
                actions = extensionActions.map { action ->
                    action.copy(
                        listener = object : WebExtensionActionController.ActionUiState.Listener {
                            override fun onClick() {
                                onDismissRequest()
                                action.listener.onClick()
                            }
                        },
                    )
                },
                scrollState = extensionActionScrollState,
                onActionMove = onExtensionActionMove,
                onActionMoveEnd = onExtensionActionMoveEnd,
                onActionMoveCancel = onExtensionActionMoveCancel,
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(text = "PCページ") },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = ResourcesR.drawable.ic_computer_24dp),
                    contentDescription = null,
                )
            },
            trailingIcon = {
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
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = ResourcesR.drawable.ic_extension_24dp),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismissRequest()
                    onInstallExtension()
                },
            )
        }
        DropdownMenuItem(
            modifier = Modifier.testTag(BrowserToolbarMenuTestTags.FindInPageButton.testTag),
            text = {
                Text(text = "ページ内検索")
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = ResourcesR.drawable.ic_search_24dp),
                    contentDescription = null,
                )
            },
            onClick = {
                onDismissRequest()
                onFindInPage()
            },
        )
        DropdownMenuItem(
            text = {
                Text(text = "翻訳")
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = ResourcesR.drawable.ic_translate_24dp),
                    contentDescription = null,
                )
            },
            onClick = {
                onDismissRequest()
                onTranslatePage()
            },
        )
        onOpenDownloads?.let { openDownloads ->
            DropdownMenuItem(
                modifier = Modifier.testTag(BrowserToolbarMenuTestTags.DownloadsButton.testTag),
                text = {
                    Text(text = "ダウンロード")
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = ResourcesR.drawable.ic_download_24dp),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismissRequest()
                    openDownloads()
                },
            )
        }
        if (showAddToHomeScreen) {
            DropdownMenuItem(
                text = {
                    Text(text = "ホームに追加")
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = ResourcesR.drawable.ic_add_to_home_screen_24dp),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismissRequest()
                    onAddToHomeScreen()
                },
            )
        }
        onOpenInBrowser?.let { openInBrowser ->
            DropdownMenuItem(
                text = {
                    Text(text = "ブラウザで開く")
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = ResourcesR.drawable.ic_open_in_browser_24dp),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismissRequest()
                    openInBrowser()
                },
            )
        }
        onOpenDevTools?.let { openDevTools ->
            DropdownMenuItem(
                modifier = Modifier.testTag(BrowserToolbarMenuTestTags.DevToolsButton.testTag),
                text = {
                    Text(text = "開発者ツール")
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = ResourcesR.drawable.ic_code_24dp),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismissRequest()
                    openDevTools()
                },
            )
        }
        if (showOpenSettings) {
            DropdownMenuItem(
                text = {
                    Text(text = "設定")
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = ResourcesR.drawable.ic_settings_24dp),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.testTag(BrowserToolbarMenuTestTags.SettingsButton.testTag),
                onClick = {
                    onDismissRequest()
                    onOpenSettings()
                },
            )
        }
    }
    if (isProfileDialogVisible && profileSwitcher != null) {
        ProfileManagementDialog(
            uiState = profileSwitcher,
            onDismiss = { isProfileDialogVisible = false },
        )
    }
    if (isMoveDialogVisible && profileSwitcher != null && onMoveTabToProfile != null) {
        MoveTabToProfileDialog(
            profiles = profileSwitcher.profiles,
            onSelect = { profileId ->
                isMoveDialogVisible = false
                onDismissRequest()
                onMoveTabToProfile(profileId)
            },
            onDismiss = { isMoveDialogVisible = false },
        )
    }
}

/**
 * アイコン下のラベル。weight で隣接する列とラベル同士がくっつかないよう、
 * 行の高さは変えずに左右へ最小限の余白を確保する
 */
@Composable
private fun MenuColumnLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier.padding(horizontal = 4.dp),
        text = text,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ToolbarMenuContentPreview(
    isPageLoading: Boolean,
    onStopLoading: () -> Unit,
    showInstallExtensionItem: Boolean,
    extensionActions: List<WebExtensionActionController.ActionUiState>,
    extensionActionScrollState: ScrollState?,
    showOpenSettings: Boolean,
    showAddToHomeScreen: Boolean,
    onOpenInBrowser: (() -> Unit)?,
    onOpenDownloads: (() -> Unit)?,
    onOpenDevTools: (() -> Unit)?,
    profileSwitcher: ProfileSwitcherUiState? = PreviewProfileSwitcherUiStateForMenu,
) {
    ToolbarMenuContent(
        onDismissRequest = {},
        onRefresh = {},
        onSuperRefresh = {},
        isPageLoading = isPageLoading,
        onStopLoading = onStopLoading,
        onHome = {},
        onForward = {},
        canGoForward = true,
        onBack = {},
        canGoBack = true,
        onLongPressHistory = {},
        isPcMode = false,
        onPcModeToggle = {},
        showInstallExtensionItem = showInstallExtensionItem,
        onInstallExtension = {},
        onTranslatePage = {},
        onShare = {},
        onFindInPage = {},
        onOpenSettings = {},
        onAddToHomeScreen = {},
        pageZoomPercent = 100,
        onPageZoomIn = {},
        onPageZoomOut = {},
        onResetPageZoom = {},
        extensionActions = extensionActions,
        extensionActionScrollState = extensionActionScrollState,
        onExtensionActionMove = { _, _ -> },
        onExtensionActionMoveEnd = {},
        onExtensionActionMoveCancel = {},
        showOpenSettings = showOpenSettings,
        showAddToHomeScreen = showAddToHomeScreen,
        showHome = true,
        onOpenInBrowser = onOpenInBrowser,
        onOpenSiteSettings = {},
        onOpenDownloads = onOpenDownloads,
        onOpenDevTools = onOpenDevTools,
        profileSwitcher = profileSwitcher,
        onMoveTabToProfile = profileSwitcher?.let { { _ -> } },
    )
}

@Preview(name = "ToolbarMenuLight")
@Preview(name = "ToolbarMenuDark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewToolbarMenuContent() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        Surface(modifier = Modifier.width(280.dp)) {
            ToolbarMenuContentPreview(
                isPageLoading = false,
                onStopLoading = {},
                showInstallExtensionItem = true,
                extensionActions = List(5) { index ->
                    WebExtensionActionController.ActionUiState(
                        extensionId = "extension_$index",
                        title = "拡張機能 $index",
                        icon = null,
                        badgeText = if (index == 0) "3" else null,
                        isEnabled = index != 1,
                        listener = PreviewActionListener,
                    )
                },
                extensionActionScrollState = ScrollState(initial = 0),
                showOpenSettings = true,
                showAddToHomeScreen = true,
                onOpenInBrowser = null,
                onOpenDownloads = {},
                onOpenDevTools = {},
            )
        }
    }
}

@Preview(name = "ToolbarMenuWebAppLight")
@Preview(name = "ToolbarMenuWebAppDark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewToolbarMenuContentWebApp() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        Surface(modifier = Modifier.width(280.dp)) {
            ToolbarMenuContentPreview(
                isPageLoading = false,
                onStopLoading = {},
                showInstallExtensionItem = false,
                extensionActions = listOf(),
                extensionActionScrollState = null,
                showOpenSettings = false,
                showAddToHomeScreen = false,
                onOpenInBrowser = {},
                onOpenDownloads = null,
                onOpenDevTools = null,
                profileSwitcher = null,
            )
        }
    }
}

@Preview(name = "ToolbarMenuConstrainedHeight")
@Preview(name = "ToolbarMenuConstrainedHeightDark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewToolbarMenuContentConstrainedHeight() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        val scrollState = rememberScrollState()
        // キーボード表示時と同様に縦方向の表示領域が狭い状態を再現する
        Surface(
            modifier = Modifier
                .width(280.dp)
                .heightIn(max = 320.dp)
                .toolbarMenuScrollbar(scrollState)
                .verticalScroll(scrollState),
        ) {
            ToolbarMenuContentPreview(
                isPageLoading = false,
                onStopLoading = {},
                showInstallExtensionItem = true,
                extensionActions = listOf(),
                extensionActionScrollState = null,
                showOpenSettings = true,
                showAddToHomeScreen = true,
                onOpenInBrowser = null,
                onOpenDownloads = {},
                onOpenDevTools = {},
            )
        }
    }
}

@Preview(name = "ToolbarMenuConstrainedHeightScrolled")
@Preview(name = "ToolbarMenuConstrainedHeightScrolledDark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewToolbarMenuContentConstrainedHeightScrolled() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        val scrollState = rememberScrollState()
        LaunchedEffect(Unit) {
            scrollState.scrollTo(scrollState.maxValue)
        }
        Surface(
            modifier = Modifier
                .width(280.dp)
                .heightIn(max = 320.dp)
                .toolbarMenuScrollbar(scrollState)
                .verticalScroll(scrollState),
        ) {
            ToolbarMenuContentPreview(
                isPageLoading = false,
                onStopLoading = {},
                showInstallExtensionItem = true,
                extensionActions = listOf(),
                extensionActionScrollState = null,
                showOpenSettings = true,
                showAddToHomeScreen = true,
                onOpenInBrowser = null,
                onOpenDownloads = {},
                onOpenDevTools = {},
            )
        }
    }
}

@Preview(name = "ToolbarMenuLoadingLight")
@Preview(name = "ToolbarMenuLoadingDark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewToolbarMenuContentLoading() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        Surface(modifier = Modifier.width(280.dp)) {
            ToolbarMenuContentPreview(
                isPageLoading = true,
                onStopLoading = {},
                showInstallExtensionItem = true,
                extensionActions = listOf(),
                extensionActionScrollState = ScrollState(initial = 0),
                showOpenSettings = true,
                showAddToHomeScreen = true,
                onOpenInBrowser = null,
                onOpenDownloads = {},
                onOpenDevTools = {},
            )
        }
    }
}

sealed interface BrowserToolbarMenuTestTags {
    val id: String
    val testTag get() = "${BrowserToolbarMenuTestTags::class.java.name}#$id"

    object ZoomLabel : BrowserToolbarMenuTestTags {
        override val id = "zoom_label"
    }
    object ZoomPercentButton : BrowserToolbarMenuTestTags {
        override val id = "zoom_percent_button"
    }
    object RefreshButton : BrowserToolbarMenuTestTags {
        override val id = "refresh_button"
    }
    object FindInPageButton : BrowserToolbarMenuTestTags {
        override val id = "find_in_page_button"
    }
    object SiteSettingsButton : BrowserToolbarMenuTestTags {
        override val id = "site_settings_button"
    }
    object DownloadsButton : BrowserToolbarMenuTestTags {
        override val id = "downloads_button"
    }
    object ShareButton : BrowserToolbarMenuTestTags {
        override val id = "share_button"
    }
    object HomeButton : BrowserToolbarMenuTestTags {
        override val id = "home_button"
    }
    object DevToolsButton : BrowserToolbarMenuTestTags {
        override val id = "dev_tools_button"
    }
    object SettingsButton : BrowserToolbarMenuTestTags {
        override val id = "settings_button"
    }
    object ProfileSwitchButton : BrowserToolbarMenuTestTags {
        override val id = "profile_switch_button"
    }
    object MoveTabToProfileButton : BrowserToolbarMenuTestTags {
        override val id = "move_tab_to_profile_button"
    }
}
