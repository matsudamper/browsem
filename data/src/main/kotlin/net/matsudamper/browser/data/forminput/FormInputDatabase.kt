package net.matsudamper.browser.data.forminput

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FormFieldValueEntity::class], version = 1, exportSchema = true)
abstract class FormInputDatabase : RoomDatabase() {
    abstract fun formInputDao(): FormInputDao

    companion object {
        const val SCHEMA_VERSION: Int = 1

        @Volatile
        private var instance: FormInputDatabase? = null

        fun getInstance(context: Context): FormInputDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FormInputDatabase::class.java,
                    "form_input.db",
                ).build().also { instance = it }
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
