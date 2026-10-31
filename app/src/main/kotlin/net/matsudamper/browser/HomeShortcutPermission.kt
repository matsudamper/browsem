package net.matsudamper.browser

import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Xiaomi (MIUI / HyperOS) 独自の「ホーム画面ショートカット」権限。
 * 拒否されていると requestPinShortcut は例外も確認ダイアログも出さずに捨てられ、
 * isRequestPinShortcutSupported も true のままなので、アプリ側で事前に判定するしかない。
 */
internal object HomeShortcutPermission {
    // MIUI が独自に追加した AppOps の op 番号。公開 API ではないため、判定できない端末では拒否扱いにしない
    private const val OP_MIUI_INSTALL_SHORTCUT = 10017

    fun isDenied(context: Context): Boolean {
        if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return false
        val mode = checkMiuiInstallShortcutMode(context) ?: return false
        return mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED
    }

    fun openSettings(context: Context) {
        val miuiPermissionEditorIntent = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val appDetailsIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // HyperOS ではセキュリティアプリの権限画面を外部から開けないことがあるため、アプリ情報画面に逃がす
        val isMiuiEditorOpened = try {
            context.startActivity(miuiPermissionEditorIntent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (!isMiuiEditorOpened) {
            context.startActivity(appDetailsIntent)
        }
    }

    private fun checkMiuiInstallShortcutMode(context: Context): Int? {
        val appOpsManager = context.getSystemService(AppOpsManager::class.java) ?: return null
        return try {
            val checkOpNoThrow = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            checkOpNoThrow.invoke(appOpsManager, OP_MIUI_INSTALL_SHORTCUT, Process.myUid(), context.packageName) as? Int
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
