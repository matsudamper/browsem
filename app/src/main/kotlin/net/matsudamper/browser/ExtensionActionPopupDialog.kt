package net.matsudamper.browser

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.mozilla.geckoview.GeckoView
import net.matsudamper.browser.resources.R as ResourcesR

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
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.7f)
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
                        text = popup.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        modifier = Modifier
                            .testTag(ExtensionActionPopupDialogTestTags.CloseButton.testTag),
                        onClick = onDismissRequest,
                    ) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.close_24dp),
                            contentDescription = "閉じる",
                        )
                    }
                }
                HorizontalDivider()
                Box(modifier = Modifier.weight(1f)) {
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
    }
}

sealed interface ExtensionActionPopupDialogTestTags {
    val id: String
    val testTag get() = "${ExtensionActionPopupDialogTestTags::class.java.name}#$id"

    object Dialog : ExtensionActionPopupDialogTestTags { override val id = "extension_popup_dialog" }
    object CloseButton : ExtensionActionPopupDialogTestTags { override val id = "extension_popup_close" }
}
