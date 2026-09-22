package net.matsudamper.browser.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import java.io.Serializable as JavaSerializable

/**
 * 外側シェルのナビゲーション先。
 * Root がモード別のブラウジング画面、それ以外は全モード共有の全画面系。
 */
@Serializable
sealed interface AppDestination : NavKey, JavaSerializable {
    @Serializable
    data object Root : AppDestination, JavaSerializable

    @Serializable
    data object Settings : AppDestination, JavaSerializable

    @Serializable
    data object SiteSettingsList : AppDestination, JavaSerializable

    @Serializable
    data class SiteSettings(
        val host: String,
        val scheme: String = "https",
        val port: Int = 443,
        val tabId: String? = null,
    ) : AppDestination, JavaSerializable

    @Serializable
    data class SiteFormInputPaths(
        val scheme: String,
        val host: String,
        val port: Int,
    ) : AppDestination

    @Serializable
    data class SiteFormInputPath(
        val scheme: String,
        val host: String,
        val port: Int,
        val path: String,
    ) : AppDestination

    @Serializable
    data class SiteFormInputField(
        val scheme: String,
        val host: String,
        val port: Int,
        val path: String,
        val fieldKey: String,
    ) : AppDestination

    @Serializable
    data object Extensions : AppDestination, JavaSerializable

    @Serializable
    data class ExtensionSettings(
        val extensionName: String,
        val optionsPageUrl: String,
    ) : AppDestination, JavaSerializable

    @Serializable
    data object History : AppDestination, JavaSerializable

    @Serializable
    data object Downloads : AppDestination, JavaSerializable

    @Serializable
    data object Addresses : AppDestination, JavaSerializable

    @Serializable
    data object CrashLogs : AppDestination, JavaSerializable

    @Serializable
    data class CrashLogDetail(val crashLogId: Long) : AppDestination, JavaSerializable

    @Serializable
    data class AddressEdit(val addressId: Long) : AppDestination, JavaSerializable

    @Serializable
    data class BackupProgress(val isImport: Boolean) : AppDestination, JavaSerializable
}

/**
 * 本体ブラウザの内側ナビゲーション先。
 * タブ切替・タブ一覧・復元の状態機械を閉じ込める。
 */
@Serializable
sealed interface BrowserNavDestination : NavKey, JavaSerializable {
    @Serializable
    data object Setup : BrowserNavDestination, JavaSerializable

    @Serializable
    data class Browser(val tabId: String, val beforeTab: Browser?) : BrowserNavDestination, JavaSerializable

    @Serializable
    data object Tabs : BrowserNavDestination, JavaSerializable
}
