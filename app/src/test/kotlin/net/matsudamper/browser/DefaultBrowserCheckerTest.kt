package net.matsudamper.browser

import android.app.role.RoleManager
import android.content.Context
import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultBrowserCheckerTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `デフォルトブラウザのロールを保持している場合は true を返す`() {
        val roleManager = context.getSystemService(RoleManager::class.java)
        shadowOf(roleManager).addHeldRole(RoleManager.ROLE_BROWSER)

        assertTrue(DefaultBrowserChecker.isDefaultBrowser(context))
    }

    @Test
    fun `デフォルトブラウザのロールを保持していない場合は false を返す`() {
        assertFalse(DefaultBrowserChecker.isDefaultBrowser(context))
    }

    @Test
    fun `ロール要求用の Intent を生成できる`() {
        val roleManager = context.getSystemService(RoleManager::class.java)
        shadowOf(roleManager).addAvailableRole(RoleManager.ROLE_BROWSER)

        val intent = DefaultBrowserChecker.createRequestDefaultBrowserIntent(context)

        assertNotNull(intent)
        assertTrue(intent!!.action != Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    }

    @Test
    fun `ロールが利用不可の場合はデフォルトアプリ設定画面の Intent を返す`() {
        val intent = DefaultBrowserChecker.createRequestDefaultBrowserIntent(context)

        assertNotNull(intent)
        assertTrue(intent!!.action == Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    }
}
