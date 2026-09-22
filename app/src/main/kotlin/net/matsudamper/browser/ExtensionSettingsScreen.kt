package net.matsudamper.browser

import android.content.res.Configuration
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.resources.R as ResourcesR
import net.matsudamper.browser.ui.common.BrowserTheme
import net.matsudamper.browser.ui.common.StatusBarAppearanceEffect
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

sealed interface ExtensionSettingsScreenTestTags {
    val id: String

    val testTag get() = "${ExtensionSettingsScreenTestTags::class.java.name}#$id"

    object Page : ExtensionSettingsScreenTestTags {
        override val id = "extension_settings_page"
    }
}

/**
 * 拡張機能の設定ページ (options_ui) を表示する画面。
 *
 * 設定ページはブラウザのタブではなくこの画面だけのセッションで開くため、
 * URL バーやタブ操作は持たず、ページが要求するプロンプトだけを扱う。
 */
@Composable
internal fun ExtensionSettingsScreen(
    extensionName: String,
    session: GeckoSession,
    onBack: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val dialogState = remember(coroutineScope) { PromptDialogState(coroutineScope) }

    DisposableEffect(session, dialogState) {
        session.promptDelegate = dialogState.createPromptDelegate()
        onDispose {
            session.promptDelegate = null
        }
    }

    DisposableEffect(session, onOpenExternalUrl) {
        // 設定ページから出る遷移でこの画面が別サイトに化けないよう、外側のブラウザへ渡す。
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny> {
                return if (isExtensionPageUri(request.uri)) {
                    GeckoResult.allow()
                } else {
                    onOpenExternalUrl(request.uri)
                    GeckoResult.deny()
                }
            }
        }
        onDispose {
            session.navigationDelegate = null
        }
    }

    FilePromptLaunchEffect(dialogState = dialogState)
    PromptDialogLayer(dialogState = dialogState)

    ExtensionSettingsScaffold(
        extensionName = extensionName,
        onBack = onBack,
        modifier = modifier,
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ExtensionSettingsScreenTestTags.Page.testTag),
            factory = { context ->
                GeckoView(context).also { geckoView ->
                    geckoView.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    geckoView.setSession(session)
                    // 非アクティブのままだと Compositor が描画を開始せず白画面になる
                    session.setActive(true)
                }
            },
            onRelease = { geckoView ->
                // 画面を離れる際にセッションが先に閉じられている場合があるため保護する
                runCatching { session.setActive(false) }
                runCatching { geckoView.releaseSession() }
            },
        )
    }
}

/**
 * 画面の枠。ページ本体 ([content]) は GeckoView のため、Preview から差し替えられるようスロットで受け取る。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtensionSettingsScaffold(
    extensionName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    StatusBarAppearanceEffect(MaterialTheme.colorScheme.surface)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = extensionName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_arrow_back_24dp),
                            contentDescription = "戻る",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            content()
        }
    }
}

/** 設定ページ内の遷移として扱う URI か。拡張機能のページと空白ページだけをこの画面で開く。 */
private fun isExtensionPageUri(uri: String): Boolean {
    return uri.startsWith("moz-extension://") || uri == "about:blank"
}

@Preview(name = "ExtensionSettingsScreenLight", widthDp = 412, heightDp = 915)
@Preview(name = "ExtensionSettingsScreenDark", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ExtensionSettingsScreenPreview() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        ExtensionSettingsScaffold(
            extensionName = "uBlock Origin",
            onBack = {},
        ) {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}
