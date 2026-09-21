package net.matsudamper.browser.screen.sitesettings

import net.matsudamper.browser.TabSecurityInfo
import net.matsudamper.browser.data.forminput.FormInputOrigin

/** [SiteSettingsScreenViewModel] を DI で生成するときに画面から渡す値。 */
internal data class SiteSettingsScreenParams(
    val host: String,
    val formInputOrigin: FormInputOrigin,
    val securityInfo: TabSecurityInfo?,
)
