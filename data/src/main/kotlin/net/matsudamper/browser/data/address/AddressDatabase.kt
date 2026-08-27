package net.matsudamper.browser.data.address

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AddressEntity::class], version = 1, exportSchema = true)
abstract class AddressDatabase : RoomDatabase() {
    abstract fun addressDao(): AddressDao

    companion object {
        const val SCHEMA_VERSION: Int = 1

        @Volatile
        private var instance: AddressDatabase? = null

        fun getInstance(context: Context): AddressDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AddressDatabase::class.java,
                    "address.db",
                ).build().also { instance = it }
            }
        }

        /** バックアップから address.db を上書き復元する直前に Room 接続を解放する */
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
