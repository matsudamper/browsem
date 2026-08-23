package net.matsudamper.browser.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * 外側シェルのナビゲーション先。
 * Root がモード別のブラウジング画面、それ以外は全モード共有の全画面系。
 */
@Serializable
sealed interface AppDestination : NavKey, java.io.Serializable {
    @Serializable
    data object Root : AppDestination, java.io.Serializable

    @Serializable
    data object Settings : AppDestination, java.io.Serializable

    @Serializable
    data class SiteSettings(val host: String, val tabId: String? = null) : AppDestination, java.io.Serializable

    @Serializable
    data object Extensions : AppDestination, java.io.Serializable

    @Serializable
    data object History : AppDestination, java.io.Serializable

    @Serializable
    data object Downloads : AppDestination, java.io.Serializable

    @Serializable
    data class BackupProgress(val isImport: Boolean) : AppDestination, java.io.Serializable
}

/**
 * 本体ブラウザの内側ナビゲーション先。
 * タブ切替・タブ一覧・復元の状態機械を閉じ込める。
 */
@Serializable
sealed interface BrowserNavDestination : NavKey, java.io.Serializable {
    @Serializable
    data object Setup : BrowserNavDestination, java.io.Serializable

    @Serializable
    data class Browser(val tabId: String, val beforeTab: Browser?) : BrowserNavDestination, java.io.Serializable

    @Serializable
    data object Tabs : BrowserNavDestination, java.io.Serializable
}
