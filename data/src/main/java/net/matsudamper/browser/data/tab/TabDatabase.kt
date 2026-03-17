package net.matsudamper.browser.data.tab

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TabStateEntity::class, TabGroupEntity::class], version = 2, exportSchema = false)
abstract class TabDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao
    abstract fun tabGroupDao(): TabGroupDao

    companion object {
        @Volatile
        private var instance: TabDatabase? = null

        /** v1→v2: tab_group テーブルの追加と tab_state への groupId カラム追加 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tab_group` (`groupId` TEXT NOT NULL, `name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`groupId`))",
                )
                db.execSQL("ALTER TABLE `tab_state` ADD COLUMN `groupId` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): TabDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TabDatabase::class.java,
                    "tab.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
        }
    }
}
