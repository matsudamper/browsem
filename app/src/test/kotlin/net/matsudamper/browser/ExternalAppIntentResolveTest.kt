package net.matsudamper.browser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * intent:// スキームのディープリンクを解決する際の分岐を検証する。
 * compileSdk が Robolectric の対応上限を超えているため sdk を明示する。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalAppIntentResolveTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `インストール済みアプリのintentリンクはアプリ起動になる`() {
        installApp(
            packageName = TARGET_PACKAGE,
            scheme = "targetapp",
        )

        val action = resolveExternalAppNavigationAction(
            context = context,
            uri = "intent://open?id=1#Intent;scheme=targetapp;package=$TARGET_PACKAGE;" +
                "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback;end",
        )

        val launch = action as ExternalAppNavigationAction.Launch
        assertEquals(TARGET_PACKAGE, launch.request.intent.`package`)
        assertEquals("targetapp://open?id=1", launch.request.intent.data.toString())
        // 起動に失敗した場合に備えて fallback URL を保持する
        assertEquals("https://example.com/fallback", launch.request.fallbackUrl)
        // browser_fallback_url は外部アプリへ渡さない
        assertNull(launch.request.intent.getStringExtra("browser_fallback_url"))
    }

    @Test
    fun `未インストールでfallbackURLがある場合はfallbackURLを開く`() {
        val action = resolveExternalAppNavigationAction(
            context = context,
            uri = "intent://open?id=1#Intent;scheme=targetapp;package=$TARGET_PACKAGE;" +
                "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback;end",
        )

        assertEquals(
            ExternalAppNavigationAction.OpenFallback("https://example.com/fallback"),
            action,
        )
    }

    @Test
    fun `未インストールでfallbackURLが無い場合はPlayストアを開く`() {
        installApp(packageName = PLAY_STORE_PACKAGE, scheme = "market")

        val action = resolveExternalAppNavigationAction(
            context = context,
            uri = "intent://open?id=1#Intent;scheme=targetapp;package=$TARGET_PACKAGE;end",
        )

        val launch = action as ExternalAppNavigationAction.Launch
        assertEquals(
            "market://details?id=$TARGET_PACKAGE",
            launch.request.intent.data.toString(),
        )
        assertEquals(
            "https://play.google.com/store/apps/details?id=$TARGET_PACKAGE",
            launch.request.fallbackUrl,
        )
    }

    @Test
    fun `Playストアが無くpackage指定も無い場合は起動を試みる`() {
        val action = resolveExternalAppNavigationAction(
            context = context,
            uri = "intent://open?id=1#Intent;scheme=targetapp;end",
        )

        // パッケージ可視性制限で解決できないだけの可能性があるため起動自体は試みる
        val launch = action as ExternalAppNavigationAction.Launch
        assertEquals("targetapp://open?id=1", launch.request.intent.data.toString())
        assertNull(launch.request.appName)
    }

    @Test
    fun `scheme指定が無いintentリンクはfallbackURLを開く`() {
        val action = resolveExternalAppNavigationAction(
            context = context,
            uri = "intent://open?id=1#Intent;package=$TARGET_PACKAGE;" +
                "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback;end",
        )

        assertEquals(
            ExternalAppNavigationAction.OpenFallback("https://example.com/fallback"),
            action,
        )
    }

    @Test
    fun `intentフラグメントが無いリンクは起動を試みずアプリ未検出とする`() {
        val action = resolveExternalAppNavigationAction(
            context = context,
            uri = "intent://open?_branch_referrer=ABC",
        )

        assertEquals(ExternalAppNavigationAction.AppNotFound, action)
    }

    @Test
    fun `javascriptのfallbackURLは使用しない`() {
        val action = resolveExternalAppNavigationAction(
            context = context,
            uri = "intent://open?id=1#Intent;S.browser_fallback_url=javascript%3Aalert(1);end",
        )

        assertEquals(ExternalAppNavigationAction.AppNotFound, action)
    }

    @Test
    fun `ブラウザが扱うスキームは外部アプリ起動にしない`() {
        assertTrue(
            resolveExternalAppNavigationAction(context, "about:blank")
                is ExternalAppNavigationAction.AllowInBrowser,
        )
    }

    private fun installApp(packageName: String, scheme: String) {
        val component = ComponentName(packageName, "$packageName.MainActivity")
        val shadowPackageManager = Shadows.shadowOf(context.packageManager)
        shadowPackageManager.addActivityIfNotPresent(component)
        shadowPackageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme(scheme)
            },
        )
    }

    companion object {
        private const val TARGET_PACKAGE = "net.matsudamper.browser.test.target"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
    }
}
