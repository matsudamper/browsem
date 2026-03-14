package net.matsudamper.browser.data.tab

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TabStateEntity::class, TabGroupEntity::class], version = 3, exportSchema = false)
abstract class TabDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao
    abstract fun tabGroupDao(): TabGroupDao

    companion object {
        @Volatile
        private var instance: TabDatabase? = null

        fun getInstance(context: Context): TabDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TabDatabase::class.java,
                    "tab.db",
                )
                    .fallbackToDestructiveMigration() // 互換性不要なので完全再構築
                    .build().also { instance = it }
            }
        }
    }
}
