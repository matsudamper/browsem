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

internal val FORM_INPUT_DATABASE_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // フィールド単位の ON/OFF を廃止し、登録済みフィールドのみ残す。
        db.execSQL(
            """
            DELETE FROM form_field_value
            WHERE NOT EXISTS (
                SELECT 1 FROM form_input_preference
                WHERE form_input_preference.scheme = form_field_value.scheme
                  AND form_input_preference.host = form_field_value.host
                  AND form_input_preference.port = form_field_value.port
                  AND form_input_preference.path = form_field_value.path
                  AND form_input_preference.fieldKey = form_field_value.fieldKey
                  AND form_input_preference.fieldKey != ''
                  AND form_input_preference.enabled != 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            "DELETE FROM form_input_preference WHERE fieldKey != '' AND enabled = 0",
        )
        db.execSQL(
            "UPDATE form_input_preference SET enabled = 1 WHERE fieldKey != ''",
        )
    }
}
