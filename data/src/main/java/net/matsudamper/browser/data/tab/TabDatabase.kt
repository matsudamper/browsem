package net.matsudamper.browser.data.tab

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TabStateEntity::class], version = 1, exportSchema = false)
abstract class TabDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao

    companion object {
        @Volatile
        private var instance: TabDatabase? = null

        fun getInstance(context: Context): TabDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TabDatabase::class.java,
                    "tab.db",
                ).build().also { instance = it }
            }
        }
    }
}
