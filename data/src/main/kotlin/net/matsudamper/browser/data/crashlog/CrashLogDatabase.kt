package net.matsudamper.browser.data.crashlog

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CrashLogEntity::class], version = 1, exportSchema = true)
abstract class CrashLogDatabase : RoomDatabase() {
    abstract fun crashLogDao(): CrashLogDao

    companion object {
        const val SCHEMA_VERSION: Int = 1

        @Volatile
        private var instance: CrashLogDatabase? = null

        fun getInstance(context: Context): CrashLogDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CrashLogDatabase::class.java,
                    "crash_log.db",
                )
                    // クラッシュハンドラはメインスレッドから同期保存するため許可する
                    .allowMainThreadQueries()
                    .build().also { instance = it }
            }
        }

        fun closeInstance() {
            val toClose = synchronized(this) {
                val current = instance
                instance = null
                current
            }
            toClose?.close()
        }
    }
}
