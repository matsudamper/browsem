package net.matsudamper.browser

import android.content.res.Configuration
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.ui.common.BrowserTheme
import net.matsudamper.browser.resources.R as ResourcesR
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** 決済ポップアップ本体の幅の上限 */
private val PAYMENT_POPUP_CONTENT_MAX_WIDTH = 480.dp

/** 決済ポップアップ本体の高さの上限 */
private val PAYMENT_POPUP_CONTENT_MAX_HEIGHT = 720.dp

/** タイトル行の高さ */
private val PAYMENT_POPUP_HEADER_HEIGHT = 48.dp

/** タイトル行と本体の区切り線の高さ */
private val PAYMENT_POPUP_DIVIDER_HEIGHT = 1.dp

/**
 * Google Pay などの決済・認証ポップアップを表示するダイアログ。
 * window.open 元のタブは前面のまま維持し、opener 連携を壊さない。
 */
@Composable
internal fun PaymentPopupDialog(
    popup: PaymentPopupRequest,
    promptDelegate: GeckoSession.PromptDelegate,
    popupCallbacks: BrowserSessionStateCallbacks,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val popupTab = popup.tab
    val popupSession = popupTab.session

    DisposableEffect(popupTab, popupCallbacks, promptDelegate) {
        popupTab.attachSessionCallbacks(
            callbacks = popupCallbacks,
            onOpenNewSessionRequest = { uri ->
                // 決済フロー中の入れ子ポップアップは同一セッションで読み込む
                if (isPaymentPopupUrl(uri)) {
                    popupSession.loadUri(uri)
                }
                GeckoResult.fromValue<GeckoSession>(null)
            },
            onCloseRequest = onDismissRequest,
        )
        popupSession.promptDelegate = promptDelegate
        onDispose {
            popupTab.detachSessionCallbacks()
            popupSession.promptDelegate = null
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            PaymentPopupContent(
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
                            geckoView.setSession(popupSession)
                            popupSession.setActive(true)
                        }
                    },
                    onRelease = { geckoView ->
                        runCatching { popupSession.setActive(false) }
                        runCatching { geckoView.releaseSession() }
                    },
                )
            }
        }
    }
}

@Composable
private fun PaymentPopupContent(
    title: String,
    onCloseRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val popupWidth = minOf(
            PAYMENT_POPUP_CONTENT_MAX_WIDTH,
            maxWidth,
        )
        Surface(
            modifier = Modifier
                .width(popupWidth)
                .heightIn(max = PAYMENT_POPUP_HEADER_HEIGHT + PAYMENT_POPUP_DIVIDER_HEIGHT + PAYMENT_POPUP_CONTENT_MAX_HEIGHT)
                .fillMaxHeight(0.92f)
                .testTag(PaymentPopupDialogTestTags.Dialog.testTag),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PAYMENT_POPUP_HEADER_HEIGHT)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                    IconButton(
                        onClick = onCloseRequest,
                        modifier = Modifier.testTag(PaymentPopupDialogTestTags.CloseButton.testTag),
                    ) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.close_24dp),
                            contentDescription = "閉じる",
                        )
                    }
                }
                HorizontalDivider(thickness = PAYMENT_POPUP_DIVIDER_HEIGHT)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    content()
                }
            }
        }
    }
}

@Preview(name = "PaymentPopupLight", widthDp = 412, heightDp = 915)
@Preview(name = "PaymentPopupDark", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewPaymentPopup() {
    BrowserTheme(themeMode = ThemeMode.THEME_LIGHT) {
        PaymentPopupContent(
            title = "Google Pay",
            onCloseRequest = {},
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "決済ポップアップ")
            }
        }
    }
}

data class PaymentPopupRequest(
    val tab: BrowserTab,
    val title: String,
)

sealed interface PaymentPopupDialogTestTags {
    val id: String
    val testTag get() = "${PaymentPopupDialogTestTags::class.java.name}#$id"

    object Dialog : PaymentPopupDialogTestTags { override val id = "payment_popup_dialog" }
    object CloseButton : PaymentPopupDialogTestTags { override val id = "payment_popup_close" }
}
