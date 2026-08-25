package net.matsudamper.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.util.Locale

internal data class PendingExternalAppLaunch(
    val sourceUri: String,
    val intent: Intent,
    val appName: String?,
    val fallbackUrl: String?,
)

internal sealed interface ExternalAppNavigationAction {
    data object AllowInBrowser : ExternalAppNavigationAction
    data class Launch(val request: PendingExternalAppLaunch) : ExternalAppNavigationAction
    data class OpenFallback(val url: String) : ExternalAppNavigationAction
    data object AppNotFound : ExternalAppNavigationAction
}

internal fun resolveExternalAppNavigationAction(
    context: Context,
    uri: String,
): ExternalAppNavigationAction {
    val parsedUri = runCatching { Uri.parse(uri) }.getOrNull()
        ?: return ExternalAppNavigationAction.AllowInBrowser
    val scheme = parsedUri.scheme?.lowercase(Locale.US)
        ?: return ExternalAppNavigationAction.AllowInBrowser

    // http/https はApp Links（Play Store、YouTubeなど）のチェックを行う
    if (scheme == "http" || scheme == "https") {
        return resolveHttpSchemeAction(context, uri, parsedUri)
    }

    if (scheme in browserHandledSchemes) {
        return ExternalAppNavigationAction.AllowInBrowser
    }

    val intent = buildExternalIntent(uri = uri, parsedUri = parsedUri, scheme = scheme)
        ?: return ExternalAppNavigationAction.AllowInBrowser
    val fallbackUrl = intent.getStringExtra(EXTRA_BROWSER_FALLBACK_URL)
        ?.takeIf { it.isNotBlank() }
        ?.takeIf { url ->
            // javascript: などの危険なスキームを排除し、http/https のみ許可する
            val urlScheme = runCatching { Uri.parse(url).scheme?.lowercase(Locale.US) }.getOrNull()
            urlScheme == "http" || urlScheme == "https"
        }
    intent.removeExtra(EXTRA_BROWSER_FALLBACK_URL)

    // intent://... の fragment に scheme= が無い場合、Intent.parseUri が返すデータURIは
    // スキームを持たない（例: intent://open?x=y → //open?x=y）ためどのアプリでも解決できない。
    // 起動を試みても必ず ActivityNotFoundException になるので、代替手段へ回す。
    if (!isLaunchableIntent(intent)) {
        return resolveUnavailableAppAction(
            context = context,
            sourceUri = uri,
            intent = intent,
            fallbackUrl = fallbackUrl,
            launchable = false,
        )
    }

    val packageManager = context.packageManager
    val resolvedActivity = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ?: packageManager.resolveActivity(intent, 0)
    if (resolvedActivity == null) {
        return resolveUnavailableAppAction(
            context = context,
            sourceUri = uri,
            intent = intent,
            fallbackUrl = fallbackUrl,
            launchable = true,
        )
    }

    val appName = resolvedActivity.loadLabel(packageManager).toString().takeIf { it.isNotBlank() }
    return ExternalAppNavigationAction.Launch(
        PendingExternalAppLaunch(
            sourceUri = uri,
            intent = intent,
            appName = appName,
            fallbackUrl = fallbackUrl,
        )
    )
}

/**
 * 対象アプリが見つからなかった場合の代替手段を決定する。
 *
 * 1. browser_fallback_url が指定されていればそのURLを開く
 * 2. package が指定されていれば Play ストアのアプリページを開く
 * 3. どちらも無い場合、起動可能な Intent であれば起動を試みる
 *    （Android 11以降のパッケージ可視性制限で resolveActivity が null を返しても
 *    startActivity は成功する場合があるため）
 *
 * @param launchable Intent 自体が解決され得る形（データURIにスキームがある等）かどうか
 */
private fun resolveUnavailableAppAction(
    context: Context,
    sourceUri: String,
    intent: Intent,
    fallbackUrl: String?,
    launchable: Boolean,
): ExternalAppNavigationAction {
    if (fallbackUrl != null) {
        return ExternalAppNavigationAction.OpenFallback(fallbackUrl)
    }
    val marketLaunch = buildMarketLaunch(
        context = context,
        sourceUri = sourceUri,
        packageName = intent.`package`,
    )
    if (marketLaunch != null) {
        return ExternalAppNavigationAction.Launch(marketLaunch)
    }
    if (!launchable) {
        return ExternalAppNavigationAction.AppNotFound
    }
    return ExternalAppNavigationAction.Launch(
        PendingExternalAppLaunch(
            sourceUri = sourceUri,
            intent = intent,
            appName = null,
            fallbackUrl = null,
        )
    )
}

/**
 * Intent 単体でアプリ解決の対象になり得るかを判定する。
 * データURIにスキームが無い、または intent スキームのままの場合はどのIntentFilterにもマッチしない。
 */
private fun isLaunchableIntent(intent: Intent): Boolean {
    val dataScheme = intent.data?.scheme?.lowercase(Locale.US)
        ?: return intent.`package` != null
    return dataScheme.isNotBlank() && dataScheme != INTENT_SCHEME
}

/**
 * 未インストールのアプリに対して Play ストアのアプリページを開く Intent を作る。
 * Play ストアが利用できない端末では null を返す。
 */
private fun buildMarketLaunch(
    context: Context,
    sourceUri: String,
    packageName: String?,
): PendingExternalAppLaunch? {
    if (packageName.isNullOrBlank()) return null
    val marketUri = Uri.parse("market://details").buildUpon()
        .appendQueryParameter("id", packageName)
        .build()
    val marketIntent = Intent(Intent.ACTION_VIEW, marketUri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    val packageManager = context.packageManager
    val resolvedActivity = packageManager.resolveActivity(marketIntent, PackageManager.MATCH_DEFAULT_ONLY)
        ?: return null
    val appName = resolvedActivity.loadLabel(packageManager).toString().takeIf { it.isNotBlank() }
    val webStoreUrl = Uri.parse("https://play.google.com/store/apps/details").buildUpon()
        .appendQueryParameter("id", packageName)
        .build()
        .toString()
    return PendingExternalAppLaunch(
        sourceUri = sourceUri,
        intent = marketIntent,
        appName = appName,
        fallbackUrl = webStoreUrl,
    )
}

/**
 * http/https スキームの URL に対して App Links チェックを行う。
 * 汎用ブラウザではなく特定アプリが処理する場合（Play Store、YouTube など）は Launch を返す。
 *
 * 判定方法:
 * - 汎用 HTTPS URL (https://example.com/) を処理できるアプリをブラウザとして収集する
 * - 対象 URL の resolveActivity がそのブラウザ一覧に含まれない場合は App Links と判定する
 */
private fun resolveHttpSchemeAction(
    context: Context,
    uri: String,
    parsedUri: Uri,
): ExternalAppNavigationAction {
    // 決済・認証ポップアップのホストは外部アプリへ飛ばすとフローが中断されるため常にブラウザで処理する
    if (isBrowserPinnedHost(parsedUri.host)) {
        return ExternalAppNavigationAction.AllowInBrowser
    }
    val packageManager = context.packageManager
    val intent = Intent(Intent.ACTION_VIEW, parsedUri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }

    // 汎用 HTTPS URL を処理できるアプリ（ブラウザ）のパッケージ名を収集する。
    // これに含まれないアプリが resolveActivity で返ってきた場合は App Links とみなす。
    val genericBrowserIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://example.com/"),
    ).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    val browserPackages = packageManager.queryIntentActivities(
        genericBrowserIntent,
        PackageManager.MATCH_DEFAULT_ONLY,
    ).map { it.activityInfo.packageName }.toSet()

    val resolvedActivity = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)

    return if (resolvedActivity != null &&
        resolvedActivity.activityInfo.packageName !in browserPackages
    ) {
        // App Links: 非ブラウザアプリが優先的に処理するURL（Play Store、YouTube など）
        val appName = resolvedActivity.loadLabel(packageManager).toString().takeIf { it.isNotBlank() }
        ExternalAppNavigationAction.Launch(
            PendingExternalAppLaunch(
                sourceUri = uri,
                intent = intent,
                appName = appName,
                fallbackUrl = null,
            )
        )
    } else {
        ExternalAppNavigationAction.AllowInBrowser
    }
}

internal fun launchExternalApp(
    context: Context,
    request: PendingExternalAppLaunch,
): Result<Unit> {
    return runCatching {
        if (context !is Activity) {
            request.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(request.intent)
    }
}

private fun buildExternalIntent(
    uri: String,
    parsedUri: Uri,
    scheme: String,
): Intent? {
    val baseIntent = if (scheme == INTENT_SCHEME) {
        runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()
    } else {
        Intent(Intent.ACTION_VIEW, parsedUri)
    } ?: return null

    return baseIntent.apply {
        action = Intent.ACTION_VIEW
        addCategory(Intent.CATEGORY_BROWSABLE)
        component = null
        selector = null
    }
}

private const val INTENT_SCHEME = "intent"
private const val EXTRA_BROWSER_FALLBACK_URL = "browser_fallback_url"

/**
 * App Links 判定をスキップして常にブラウザ内で処理するホストかどうかを判定する。
 *
 * Google Pay はサイト上のボタンを押すと pay.google.com のポップアップを開いて決済するが、
 * このホストは端末上では Google Wallet アプリの App Links として解決されるため、
 * 外部アプリ起動扱いにすると決済ポップアップが読み込まれず決済がエラーになる。
 * 同様にポップアップ内のサインインで使われる accounts.google.com もブラウザ内で処理する。
 */
internal fun isBrowserPinnedHost(host: String?): Boolean {
    val normalized = host?.lowercase(Locale.US) ?: return false
    return browserPinnedHosts.any { pinned ->
        normalized == pinned || normalized.endsWith(".$pinned")
    }
}

private val browserPinnedHosts = setOf(
    "pay.google.com",
    "pay.sandbox.google.com",
    "payments.google.com",
    "accounts.google.com",
)

/**
 * window.open で開かれる Google Pay などの決済・認証ポップアップかどうかを判定する。
 * これらは opener タブを維持したままダイアログで表示する必要がある。
 */
internal fun isPaymentPopupUrl(uri: String): Boolean {
    val host = Uri.parse(uri).host ?: return false
    return isBrowserPinnedHost(host)
}

private val browserHandledSchemes = setOf(
    "about",
    "blob",
    "chrome",
    "data",
    "file",
    // http と https は App Links チェックのため別途処理する
    "jar",
    "javascript",
    "moz-extension",
    "resource",
    "view-source",
)
