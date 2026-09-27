package net.matsudamper.browser

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.ProfileId
import net.matsudamper.browser.data.WebAppId

@Composable
internal fun AddToHomeScreenDialogHost(
    controller: AddToHomeScreenDialogController,
    webAppShortcutManager: WebAppShortcutManager,
    pinWebAppScope: CoroutineScope,
    onRegisterWebApp: suspend (url: String, title: String, profileId: String) -> String,
) {
    val context = LocalContext.current
    val dialogState = controller.state ?: return
    val target = dialogState.target
    AddToHomeScreenDialog(
        url = target.url,
        title = target.title,
        favicon = dialogState.favicon,
        isIconLoading = dialogState.isIconLoading,
        onAddWebApp = { title ->
            pinWebAppScope.launch {
                val isPinned = pinWebAppToHome(
                    webAppShortcutManager = webAppShortcutManager,
                    target = target,
                    label = title,
                    favicon = dialogState.favicon,
                    onRegisterWebApp = onRegisterWebApp,
                )
                if (!isPinned) {
                    Toast.makeText(context, "ランチャーがショートカット追加に対応していません", Toast.LENGTH_SHORT).show()
                }
            }
            controller.dismiss()
        },
        onAddShortcut = { title ->
            pinUrlShortcutToHome(
                context = context,
                url = target.url,
                title = title,
                favicon = dialogState.favicon,
            )
            controller.dismiss()
        },
        onDismiss = controller::dismiss,
    )
}

internal fun AddToHomeScreenDialogController.requestFromPage(
    pageUrl: String,
    pageTitle: String,
    profileId: ProfileId,
    webAppManifestJson: String?,
    fallbackFavicon: android.graphics.Bitmap?,
    onIconLoaded: ((android.graphics.Bitmap?) -> Unit)? = null,
) {
    request(
        target = AddToHomeScreenTarget.NewPage(
            url = pageUrl,
            title = pageTitle,
            profileId = profileId,
        ),
        webAppManifestJson = webAppManifestJson,
        fallbackFavicon = fallbackFavicon,
        onIconLoaded = onIconLoaded,
    )
}

internal fun AddToHomeScreenDialogController.requestFromRegisteredWebApp(
    webAppId: WebAppId,
    startUrl: String,
    title: String,
    profileId: ProfileId,
) {
    request(
        target = AddToHomeScreenTarget.RegisteredWebApp(
            webAppId = webAppId,
            url = startUrl,
            title = title,
            profileId = profileId,
        ),
    )
}
