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
        // v2 は host+path のみのため origin を復元できない。誤った https:443 割り当ては避け、データを破棄する。
        db.execSQL("DROP TABLE IF EXISTS `form_field_value`")
        db.execSQL("DROP TABLE IF EXISTS `form_input_preference`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `form_field_value` (
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
            CREATE INDEX IF NOT EXISTS `index_form_field_value_scheme_host_port_path_fieldKey_createdAt`
            ON `form_field_value` (`scheme`, `host`, `port`, `path`, `fieldKey`, `createdAt`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `form_input_preference` (
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
    }
}
