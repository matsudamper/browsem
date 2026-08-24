package net.matsudamper.browser

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

internal object DefaultBrowserChecker {
    fun isDefaultBrowser(context: Context): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return true
        return roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
    }

    fun createRequestDefaultBrowserIntent(context: Context): Intent? {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
        return if (roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
            roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
        } else {
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        }
    }
}
