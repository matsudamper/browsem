package net.matsudamper.browser.data.forminput

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        FormFieldValueEntity::class,
        FormInputPreferenceEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class FormInputDatabase : RoomDatabase() {
    abstract fun formInputDao(): FormInputDao

    companion object {
        const val SCHEMA_VERSION: Int = 2

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            FORM_INPUT_DATABASE_MIGRATION_1_2,
        )

        @Volatile
        private var instance: FormInputDatabase? = null

        fun getInstance(context: Context): FormInputDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FormInputDatabase::class.java,
                    "form_input.db",
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { instance = it }
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
