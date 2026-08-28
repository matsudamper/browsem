package net.matsudamper.browser.data.forminput

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val FORM_INPUT_DATABASE_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `form_input_preference` (
                `host` TEXT NOT NULL,
                `path` TEXT NOT NULL,
                `fieldKey` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                PRIMARY KEY(`host`, `path`, `fieldKey`)
            )
            """.trimIndent(),
        )
    }
}

internal val FORM_INPUT_DATABASE_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `form_field_value_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `scheme` TEXT NOT NULL,
                `host` TEXT NOT NULL,
                `port` INTEGER NOT NULL,
                `path` TEXT NOT NULL,
                `fieldKey` TEXT NOT NULL,
                `value` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO form_field_value_new (id, scheme, host, port, path, fieldKey, value, createdAt)
            SELECT id, 'https', host, 443, path, fieldKey, value, createdAt FROM form_field_value
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE form_field_value")
        db.execSQL("ALTER TABLE form_field_value_new RENAME TO form_field_value")
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_form_field_value_scheme_host_port_path_fieldKey_createdAt`
            ON `form_field_value` (`scheme`, `host`, `port`, `path`, `fieldKey`, `createdAt`)
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `form_input_preference_new` (
                `scheme` TEXT NOT NULL,
                `host` TEXT NOT NULL,
                `port` INTEGER NOT NULL,
                `path` TEXT NOT NULL,
                `fieldKey` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                PRIMARY KEY(`scheme`, `host`, `port`, `path`, `fieldKey`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO form_input_preference_new (scheme, host, port, path, fieldKey, enabled)
            SELECT 'https', host, 443, path, fieldKey, enabled FROM form_input_preference
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE form_input_preference")
        db.execSQL("ALTER TABLE form_input_preference_new RENAME TO form_input_preference")
    }
}
