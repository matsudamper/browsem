package net.matsudamper.browser.data.sitesettings

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SiteSettingsEntity::class,
        SiteSettingsMigrationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class SiteSettingsDatabase : RoomDatabase() {
    abstract fun siteSettingsDao(): SiteSettingsDao

    companion object {
        @Volatile
        private var instance: SiteSettingsDatabase? = null

        fun getInstance(context: Context): SiteSettingsDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SiteSettingsDatabase::class.java,
                    "site_settings.db",
                ).build().also { instance = it }
            }
        }
    }
}
