package net.matsudamper.browser

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.ui.common.BrowserTheme
import org.mozilla.geckoview.GeckoSession
import net.matsudamper.browser.resources.R as ResourcesR

/**
 * Custom Tab / WebApp のようにタブ UI が無い画面で `window.open` を扱う。
 * 通常ブラウザはタブに載せるため、このスタックは使わない。
 */
@Stable
internal class WindowOpenPopupController(
    private val browserTabController: BrowserTabController,
) {
    var popups: List<BrowserTab> by mutableStateOf(emptyList())
        private set

    val top: BrowserTab?
        get() = popups.lastOrNull()

    fun open(uri: String, openerTabId: String): GeckoSession {
        val tab = browserTabController.createTabForNewSession(
            initialUrl = uri,
            openerTabId = openerTabId,
        )
        popups = popups + tab
        return tab.session
    }

    fun dismissTop() {
        val closing = popups.lastOrNull() ?: return
        popups = popups.dropLast(1)
        browserTabController.closeTab(closing.tabId)
    }

    fun dismissAll() {
        val closing = popups.asReversed()
        popups = emptyList()
        closing.forEach { tab ->
            browserTabController.closeTab(tab.tabId)
        }
    }
}

@Composable
internal fun rememberWindowOpenPopupController(
    browserTabController: BrowserTabController,
): WindowOpenPopupController {
    val controller = remember(browserTabController) {
        WindowOpenPopupController(browserTabController)
    }
    DisposableEffect(controller) {
        onDispose { controller.dismissAll() }
    }
    return controller
}

@Composable
internal fun WindowOpenOverlayDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag(WindowOpenOverlayDialogTestTags.Dialog.testTag),
        ) {
            content()
        }
    }
}

@Preview(name = "WindowOpenOverlayLight", widthDp = 412, heightDp = 915)
@Preview(name = "WindowOpenOverlayDark", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewWindowOpenOverlay() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        // Dialog は Paparazzi で描画できないため、全画面 overlay の枠だけを示す
        WindowOpenOverlayPreviewFrame()
    }
}

@Composable
private fun WindowOpenOverlayPreviewFrame() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "pay.google.com",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    modifier = Modifier.testTag(WindowOpenOverlayDialogTestTags.CloseButton.testTag),
                    onClick = {},
                ) {
                    Icon(
                        painter = painterResource(ResourcesR.drawable.close_24dp),
                        contentDescription = "閉じる",
                    )
                }
            }
            HorizontalDivider()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "window.open ポップアップ",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

sealed interface WindowOpenOverlayDialogTestTags {
    val id: String
    val testTag get() = "${WindowOpenOverlayDialogTestTags::class.java.name}#$id"

    object Dialog : WindowOpenOverlayDialogTestTags { override val id = "window_open_overlay_dialog" }
    object CloseButton : WindowOpenOverlayDialogTestTags { override val id = "window_open_overlay_close" }
}
