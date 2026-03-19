package net.matsudamper.browser

import android.net.Uri
import androidx.browser.trusted.LauncherActivity

/**
 * PWAショートカットのエントリポイントActivity。
 * LauncherActivityを継承することで、タップ時にTWA（Trusted Web Activity）として起動する。
 * LauncherActivityはChrome等のTWA対応ブラウザを探してCustom Tabs経由でURLを委譲する。
 * デジタルアセットリンク（/.well-known/assetlinks.json）で検証済みのドメインは
 * URLバーなしの全画面で表示され、未検証ドメインでもURLバー付きで開かれる。
 */
class PwaShortcutLauncherActivity : LauncherActivity() {
    override fun getLaunchingUrl(): Uri {
        return intent?.data ?: Uri.EMPTY
    }
}
