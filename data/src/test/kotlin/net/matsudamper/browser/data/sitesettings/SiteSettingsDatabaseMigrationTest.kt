package net.matsudamper.browser.data.sitesettings

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SiteSettingsDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SiteSettingsDatabase::class.java,
    )

    @Test
    fun migrate1To2_keepsExistingSettingsAndAddsUnrequestedCamera() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO site_settings " +
                    "(host, microphone, microphoneRequested, geolocation, geolocationRequested, autoplay, autoplayRequested) " +
                    "VALUES ('example.com', 1, 1, 2, 1, 2, 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            *SiteSettingsDatabase.ALL_MIGRATIONS,
        ).use { db ->
            db.query("SELECT microphone, geolocation, autoplay, camera, cameraRequested FROM site_settings").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(2, cursor.getInt(1))
                assertEquals(2, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals(0, cursor.getInt(4))
            }
        }
    }

    private companion object {
        const val TEST_DB = "site-settings-migration-test"
    }
}
