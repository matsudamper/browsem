package net.matsudamper.browser.data.sitesettings

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "site_settings")
internal data class SiteSettingsEntity(
    @PrimaryKey val host: String,
    val microphone: Int,
    val microphoneRequested: Boolean,
    val geolocation: Int,
    val geolocationRequested: Boolean,
    val autoplay: Int,
    val autoplayRequested: Boolean,
)

@Entity(tableName = "site_settings_migration")
internal data class SiteSettingsMigrationEntity(
    @PrimaryKey val id: Int,
)
