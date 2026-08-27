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
