package net.matsudamper.browser

import android.content.res.Configuration
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoView
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.ui.common.BrowserTheme
import net.matsudamper.browser.resources.R as ResourcesR

/**
 * 拡張機能のツールバーポップアップ (browser_action の default_popup) を表示する BottomSheet。
 * ポップアップは現在のタブに対して動作するため、タブごとの設定はここから操作する。
 *
 * ポップアップの中身は拡張機能の HTML で高さが決まるが、GeckoView には文書の内容の高さを
 * 取得する API がないため、PC の Firefox のように内容へフィットさせることはできない。
 * 代わりに BottomSheet の半展開を既定とし、内容が入り切らない拡張機能 (AdGuard 等) は
 * ハンドルのドラッグで全画面まで広げられるようにしている。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtensionActionPopupSheet(
    popup: WebExtensionActionController.PopupRequest,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        // 半展開 (画面の約半分) で開き、ドラッグで全画面まで広げられるようにする
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        ExtensionActionPopupContent(
            title = popup.title,
            onCloseRequest = onDismissRequest,
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
 * BottomSheet の中身。ポップアップ本体 ([content]) は GeckoView のため、
 * Preview から差し替えられるようにスロットで受け取る。
 *
 * 半展開のままでも全画面まで広げられるよう、高さは常に BottomSheet の最大高さまで使う。
 * 半展開時は下側が画面外へオフセットされるだけで GeckoView はリサイズされない。
 */
@Composable
private fun ColumnScope.ExtensionActionPopupContent(
    title: String,
    onCloseRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .testTag(ExtensionActionPopupSheetTestTags.Sheet.testTag),
    ) {
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
                    .testTag(ExtensionActionPopupSheetTestTags.CloseButton.testTag),
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

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "ExtensionActionPopupLight")
@Preview(name = "ExtensionActionPopupDark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewExtensionActionPopup() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        // BottomSheet は Paparazzi で描画できないため、半展開時の見た目を Surface で再現する
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f),
                shape = BottomSheetDefaults.ExpandedShape,
                color = BottomSheetDefaults.ContainerColor,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        BottomSheetDefaults.DragHandle()
                    }
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
    }
}

sealed interface ExtensionActionPopupSheetTestTags {
    val id: String
    val testTag get() = "${ExtensionActionPopupSheetTestTags::class.java.name}#$id"

    object Sheet : ExtensionActionPopupSheetTestTags { override val id = "extension_popup_sheet" }
    object CloseButton : ExtensionActionPopupSheetTestTags { override val id = "extension_popup_close" }
}
