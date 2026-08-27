package net.matsudamper.browser.data.forminput

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FormInputDatabaseMigrationTest {
    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FormInputDatabase::class.java,
    )

    @Test
    fun migrate1To2AddsPreferenceTable() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO form_field_value (host, path, fieldKey, value, createdAt) " +
                    "VALUES ('example.com', '/form', 'comment', 'hello', 1000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, *FormInputDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT COUNT(*) FROM form_field_value").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.execSQL(
                "INSERT INTO form_input_preference (host, path, fieldKey, enabled) " +
                    "VALUES ('example.com', '/form', '', 0)",
            )
            db.query(
                "SELECT enabled FROM form_input_preference WHERE host = 'example.com' AND path = '/form'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    companion object {
        private const val TEST_DB = "form-input-migration-test"
    }
}
