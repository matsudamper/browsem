package net.matsudamper.browser

import android.content.res.Configuration
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.mozilla.geckoview.GeckoView
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.ui.common.BrowserTheme
import net.matsudamper.browser.resources.R as ResourcesR

/** ポップアップの高さの上限。PC の Firefox のポップアップ上限 (600 CSS px) に合わせる */
private val POPUP_MAX_HEIGHT = 600.dp

/**
 * 拡張機能のツールバーポップアップ (browser_action の default_popup) を表示するダイアログ。
 * ポップアップは現在のタブに対して動作するため、タブごとの設定はここから操作する。
 */
@Composable
internal fun ExtensionActionPopupDialog(
    popup: WebExtensionActionController.PopupRequest,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        // targetSdk 35 以降は decorFitsSystemWindows が無視されるため、
        // ダイアログ側でシステムバー・IME のインセットを自前で避ける。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        ExtensionActionPopupContent(
            title = popup.title,
            onCloseRequest = onDismissRequest,
            modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    GeckoView(context).also { geckoView ->
                        geckoView.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        geckoView.setSession(popup.session)
                        // 非アクティブのままだと Compositor が描画を開始せず白画面になる
                        popup.session.setActive(true)
                    }
                },
                onRelease = { geckoView ->
                    // ダイアログを閉じる際にセッションが先に close されている場合があるため保護する
                    runCatching { popup.session.setActive(false) }
                    runCatching { geckoView.releaseSession() }
                },
            )
        }
    }
}

/**
 * ポップアップダイアログの枠。ポップアップ本体 ([content]) は GeckoView のため、
 * Preview から差し替えられるようにスロットで受け取る。
 */
@Composable
private fun ExtensionActionPopupContent(
    title: String,
    onCloseRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        // 拡張機能のポップアップは HTML 側が高さを決めるが、GeckoView には文書の内容の
        // 高さを取得する API がないためコンテンツにフィットさせられない。
        // 画面いっぱいに広げると PC のポップアップとかけ離れるので、PC の Firefox の
        // ポップアップ上限 (600 CSS px) に揃えた高さを上限とし、画面が狭ければ
        // 表示できる範囲まで縮める。
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 16.dp)
            .fillMaxWidth()
            .heightIn(max = POPUP_MAX_HEIGHT)
            .fillMaxHeight()
            .testTag(ExtensionActionPopupDialogTestTags.Dialog.testTag),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    modifier = Modifier
                        .testTag(ExtensionActionPopupDialogTestTags.CloseButton.testTag),
                    onClick = onCloseRequest,
                ) {
                    Icon(
                        painter = painterResource(ResourcesR.drawable.close_24dp),
                        contentDescription = "閉じる",
                    )
                }
            }
            HorizontalDivider()
            Box(modifier = Modifier.weight(1f)) {
                content()
            }
        }
    }
}

@Preview(name = "ExtensionActionPopupLight", widthDp = 412, heightDp = 915)
@Preview(name = "ExtensionActionPopupDark", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewExtensionActionPopup() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        // Dialog は Paparazzi で描画できないため、画面中央に置かれた見た目を再現する
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            ExtensionActionPopupContent(
                title = "AdGuard",
                onCloseRequest = {},
            ) {
                // GeckoView は Preview で描画できないため、ポップアップの表示領域を色で示す
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "拡張機能のポップアップ",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

sealed interface ExtensionActionPopupDialogTestTags {
    val id: String
    val testTag get() = "${ExtensionActionPopupDialogTestTags::class.java.name}#$id"

    object Dialog : ExtensionActionPopupDialogTestTags { override val id = "extension_popup_dialog" }
    object CloseButton : ExtensionActionPopupDialogTestTags { override val id = "extension_popup_close" }
}
