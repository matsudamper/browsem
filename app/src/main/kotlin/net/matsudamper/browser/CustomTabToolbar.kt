package net.matsudamper.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.resources.R as ResourcesR
import net.matsudamper.browser.ui.common.BrowserTheme

internal sealed interface CustomTabToolbarTestTags {
    val id: String
    val testTag get() = "${CustomTabToolbarTestTags::class.java.name}#$id"

    object Toolbar : CustomTabToolbarTestTags {
        override val id = "custom_tab_toolbar"
    }
    object PageInfo : CustomTabToolbarTestTags {
        override val id = "custom_tab_page_info"
    }
    object MenuButton : CustomTabToolbarTestTags {
        override val id = "custom_tab_menu_button"
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
internal fun CustomTabToolbar(
    title: String,
    url: String,
    onClose: () -> Unit,
    toolbarColor: Color?,
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
    onAddToHomeScreen: () -> Unit,
    showAddToHomeScreen: Boolean,
    onOpenInBrowser: (() -> Unit)?,
    // null の場合はメニューに「サイトの設定」を表示しない（設定画面のナビゲーションスタックを持たないカスタムタブ用）
    onOpenSiteSettings: (() -> Unit)?,
    pageZoomPercent: Int,
    onPageZoomIn: () -> Unit,
    onPageZoomOut: () -> Unit,
    onResetPageZoom: () -> Unit,
    isPageLoading: Boolean = false,
    onStopLoading: () -> Unit = {},
    showCloseButton: Boolean = true,
    showHome: Boolean = false,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var menuAnchorBottomPx by remember { mutableIntStateOf(0) }
    val resolvedToolbarColor = toolbarColor ?: MaterialTheme.colorScheme.primaryContainer
    val toolbarContentColor = if (resolvedToolbarColor.luminance() >= 0.5f) {
        Color.Black
    } else {
        Color.White
    }
    val toolbarSecondaryContentColor = toolbarContentColor.copy(alpha = 0.72f)
    val context = LocalContext.current

    Surface(
        color = resolvedToolbarColor,
        contentColor = toolbarContentColor,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CustomTabToolbarTestTags.Toolbar.testTag),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // ステータスバー領域はSurfaceの背景色で塗りつぶし、コンテンツをその下に押し出す
                .windowInsetsPadding(
                    WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top),
                )
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showCloseButton) {
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(ResourcesR.drawable.close_24dp),
                        contentDescription = "閉じる",
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .testTag(CustomTabToolbarTestTags.PageInfo.testTag)
                    .combinedClickable(
                        interactionSource = null,
                        indication = null,
                        onLongClickLabel = "URLをコピー",
                        onLongClick = { copyUrlToClipboard(context, url) },
                        hapticFeedbackEnabled = true,
                        onClick = {},
                    )
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    color = toolbarSecondaryContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    menuAnchorBottomPx = coordinates.boundsInWindow().bottom.roundToInt()
                },
            ) {
                IconButton(
                    modifier = Modifier.testTag(CustomTabToolbarTestTags.MenuButton.testTag),
                    onClick = { menuExpanded = true },
                ) {
                    Icon(
                        painter = painterResource(ResourcesR.drawable.ic_more_vert_24dp),
                        contentDescription = "メニュー",
                    )
                }
                ToolbarMenu(
                    visibleMenu = menuExpanded,
                    menuAnchorBottomPx = menuAnchorBottomPx,
                    onDismissRequest = { menuExpanded = false },
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
                    onOpenSettings = {},
                    onAddToHomeScreen = onAddToHomeScreen,
                    pageZoomPercent = pageZoomPercent,
                    onPageZoomIn = onPageZoomIn,
                    onPageZoomOut = onPageZoomOut,
                    onResetPageZoom = onResetPageZoom,
                    showOpenSettings = false,
                    showAddToHomeScreen = showAddToHomeScreen,
                    showHome = showHome,
                    onOpenInBrowser = onOpenInBrowser,
                    onOpenSiteSettings = onOpenSiteSettings,
                )
            }
        }
    }
}

private fun copyUrlToClipboard(context: Context, url: String) {
    if (url.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
    Toast.makeText(context, "URLをコピーしました", Toast.LENGTH_SHORT).show()
}

@Preview(name = "CustomTabToolbarUrlLongPress", widthDp = 412)
@Composable
private fun PreviewCustomTabToolbarUrlLongPress() {
    PreviewCustomTabToolbar(
        showCloseButton = true,
        showHome = false,
    )
}

@Preview(name = "WebAppToolbarUrlLongPress", widthDp = 412)
@Composable
private fun PreviewWebAppToolbarUrlLongPress() {
    PreviewCustomTabToolbar(
        showCloseButton = false,
        showHome = true,
    )
}

@Composable
private fun PreviewCustomTabToolbar(
    showCloseButton: Boolean,
    showHome: Boolean,
) {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        CustomTabToolbar(
            title = "example.com",
            url = "https://example.com/page",
            onClose = {},
            toolbarColor = null,
            onRefresh = {},
            onSuperRefresh = {},
            onHome = {},
            onForward = {},
            canGoForward = false,
            onBack = {},
            canGoBack = true,
            onLongPressHistory = {},
            isPcMode = false,
            onPcModeToggle = {},
            showInstallExtensionItem = false,
            onInstallExtension = {},
            onTranslatePage = {},
            onShare = {},
            onFindInPage = {},
            onAddToHomeScreen = {},
            showAddToHomeScreen = true,
            onOpenInBrowser = {},
            onOpenSiteSettings = {},
            pageZoomPercent = 100,
            onPageZoomIn = {},
            onPageZoomOut = {},
            onResetPageZoom = {},
            showCloseButton = showCloseButton,
            showHome = showHome,
        )
    }
}
