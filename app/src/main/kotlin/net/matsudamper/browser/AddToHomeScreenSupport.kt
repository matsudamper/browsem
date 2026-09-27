package net.matsudamper.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.ProfileId
import net.matsudamper.browser.data.WebAppId

/**
 * ブラウザのメニュー・設定のウェブアプリ一覧・ウェブアプリモードなど、
 * 起動経路が異なっても同じダイアログでホームへ追加するための対象。
 */
internal sealed interface AddToHomeScreenTarget {
    val url: String
    val title: String

    data class NewPage(
        override val url: String,
        override val title: String,
        val profileId: ProfileId,
    ) : AddToHomeScreenTarget

    data class RegisteredWebApp(
        val webAppId: WebAppId,
        override val url: String,
        override val title: String,
        val profileId: ProfileId,
    ) : AddToHomeScreenTarget
}

internal data class AddToHomeScreenDialogState(
    val target: AddToHomeScreenTarget,
    val favicon: Bitmap?,
    val isIconLoading: Boolean,
)

internal suspend fun fetchAddToHomeScreenIcon(
    pageUrl: String,
    webAppManifestJson: String? = null,
    fallbackFavicon: Bitmap? = null,
): Bitmap? {
    val fetchedIcon = try {
        HomeScreenIconFetcher.fetchIcon(
            pageUrl = pageUrl,
            webAppManifestJson = webAppManifestJson,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    return fetchedIcon ?: fallbackFavicon
}

internal class AddToHomeScreenDialogController(
    private val coroutineScope: CoroutineScope,
) {
    var state by mutableStateOf<AddToHomeScreenDialogState?>(null)
        private set

    private var iconJob: Job? = null

    fun request(
        target: AddToHomeScreenTarget,
        webAppManifestJson: String? = null,
        fallbackFavicon: Bitmap? = null,
        onIconLoaded: ((Bitmap?) -> Unit)? = null,
    ) {
        iconJob?.cancel()
        state = AddToHomeScreenDialogState(
            target = target,
            favicon = null,
            isIconLoading = true,
        )
        val pageUrl = target.url
        iconJob = coroutineScope.launch {
            val favicon = fetchAddToHomeScreenIcon(
                pageUrl = pageUrl,
                webAppManifestJson = webAppManifestJson,
                fallbackFavicon = fallbackFavicon,
            )
            try {
                val current = state ?: return@launch
                if (current.target.url != pageUrl) return@launch
                val updated = current.copy(
                    favicon = favicon,
                    isIconLoading = false,
                )
                state = updated
                onIconLoaded?.invoke(favicon)
            } finally {
                val current = state
                if (current != null && current.target.url == pageUrl && current.isIconLoading) {
                    val resolvedFavicon = current.favicon ?: fallbackFavicon
                    state = current.copy(
                        favicon = resolvedFavicon,
                        isIconLoading = false,
                    )
                    onIconLoaded?.invoke(resolvedFavicon)
                }
            }
        }
    }

    fun dismiss() {
        iconJob?.cancel()
        iconJob = null
        state = null
    }
}

internal fun requestPinRegisteredWebAppToHome(
    webAppShortcutManager: WebAppShortcutManager,
    target: AddToHomeScreenTarget.RegisteredWebApp,
): Boolean {
    return webAppShortcutManager.requestPinRegisteredWebApp(
        launchInfo = WebAppLaunchInfo(
            webAppId = target.webAppId,
            startUrl = target.url,
            profileId = target.profileId,
        ),
        label = target.title,
    )
}

internal suspend fun pinWebAppToHome(
    webAppShortcutManager: WebAppShortcutManager,
    target: AddToHomeScreenTarget,
    label: String,
    favicon: Bitmap?,
    onRegisterWebApp: suspend (url: String, title: String, profileId: String) -> String,
): Boolean {
    if (!webAppShortcutManager.isPinSupported()) return false
    val resolvedLabel = label.ifBlank { target.url }
    val launchInfo = when (target) {
        is AddToHomeScreenTarget.NewPage -> {
            val webAppId = WebAppId(onRegisterWebApp(target.url, resolvedLabel, target.profileId.value))
            WebAppLaunchInfo(
                webAppId = webAppId,
                startUrl = target.url,
                profileId = target.profileId,
            )
        }

        is AddToHomeScreenTarget.RegisteredWebApp -> {
            WebAppLaunchInfo(
                webAppId = target.webAppId,
                startUrl = target.url,
                profileId = target.profileId,
            )
        }
    }
    webAppShortcutManager.requestPin(
        launchInfo = launchInfo,
        label = resolvedLabel,
        favicon = favicon,
    )
    return true
}

internal fun pinUrlShortcutToHome(
    context: Context,
    url: String,
    title: String,
    favicon: Bitmap?,
): Boolean {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        Toast.makeText(context, "ランチャーがショートカット追加に対応していません", Toast.LENGTH_SHORT).show()
        return false
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url), context, DeepLinkActivity::class.java)
    val icon = if (favicon != null) {
        IconCompat.createWithBitmap(favicon)
    } else {
        IconCompat.createWithResource(context, R.mipmap.ic_launcher)
    }
    val resolvedTitle = title.ifBlank { url }
    val info = ShortcutInfoCompat.Builder(context, "shortcut_${url.hashCode()}")
        .setShortLabel(resolvedTitle.take(25))
        .setLongLabel(resolvedTitle)
        .setIcon(icon)
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.requestPinShortcut(context, info, null)
    return true
}
