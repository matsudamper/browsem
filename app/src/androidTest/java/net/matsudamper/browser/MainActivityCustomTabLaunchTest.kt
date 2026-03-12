package net.matsudamper.browser

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.browser.customtabs.CustomTabsSessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityCustomTabLaunchTest {

    @Test
    fun CustomTabsServiceが他アプリからbind可能なmanifest設定になっている() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val intent = Intent(ACTION_CUSTOM_TABS_SERVICE).setPackage(context.packageName)
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveService(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveService(intent, 0)
        }

        assertNotNull("CustomTabsService が見つかりません", resolveInfo)
        val serviceInfo = resolveInfo!!.serviceInfo
        assertTrue("CustomTabsService は exported=true が必要です", serviceInfo.exported)
        assertTrue(
            "CustomTabsService に bind 制限 permission を設定すると他アプリで SecurityException が発生します",
            serviceInfo.permission.isNullOrEmpty(),
        )
    }

    @Test
    fun customTabsIntentでCustomTabActivityへ遷移する() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val monitor = instrumentation.addMonitor(CustomTabActivity::class.java.name, null, false)

        try {
            val intent = Intent(context, DeepLinkActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("https://example.com")
                putExtras(
                    Bundle().apply {
                        putBinder(EXTRA_CUSTOM_TABS_SESSION, Binder())
                    }
                )
            }

            ActivityScenario.launch<DeepLinkActivity>(intent).use {
                val launched = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
                assertNotNull("CustomTabActivity が起動しませんでした", launched)
                launched?.finish()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun mayLaunchUrlで準備したセッションをCustomTabActivityが引き継ぐ() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val monitor = instrumentation.addMonitor(CustomTabActivity::class.java.name, null, false)
        CustomTabsWarmupStore.resetForTesting()

        try {
            val sessionToken = CustomTabsSessionToken.createMockSessionTokenForTesting()
            val preloadUri = Uri.parse("about:blank#customtabs-preload")

            CustomTabsWarmupStore.onNewSession(sessionToken)
            CustomTabsWarmupStore.onMayLaunchUrl(
                context = context,
                token = sessionToken,
                url = preloadUri,
            )
            assertTrue(
                "mayLaunchUrl の準備カウントが更新されませんでした",
                waitUntil(5_000) {
                    CustomTabsWarmupStore.getDebugMayLaunchCountForTesting() >= 1
                },
            )
            assertEquals(preloadUri.toString(), CustomTabsWarmupStore.getDebugLastPreparedUrlForTesting())

            val intent = Intent(context, DeepLinkActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = preloadUri
                putExtras(
                    Bundle().apply {
                        putBinder(EXTRA_CUSTOM_TABS_SESSION, extractCallbackBinder(sessionToken))
                    }
                )
            }

            ActivityScenario.launch<DeepLinkActivity>(intent).use {
                val launched = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
                assertNotNull("CustomTabActivity が起動しませんでした", launched)
                assertTrue(
                    "事前ロード済みセッションが CustomTabActivity に引き継がれていません",
                    waitUntil(10_000) {
                        CustomTabsWarmupStore.getDebugConsumeHitCountForTesting() >= 1
                    },
                )
                launched?.finish()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            CustomTabsWarmupStore.resetForTesting()
        }
    }

    private fun extractCallbackBinder(sessionToken: CustomTabsSessionToken): IBinder {
        val method = CustomTabsSessionToken::class.java.getDeclaredMethod("getCallbackBinder")
        method.isAccessible = true
        return method.invoke(sessionToken) as IBinder
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(100)
        }
        return condition()
    }

    companion object {
        private const val ACTION_CUSTOM_TABS_SERVICE = "android.support.customtabs.action.CustomTabsService"
        private const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
    }
}
