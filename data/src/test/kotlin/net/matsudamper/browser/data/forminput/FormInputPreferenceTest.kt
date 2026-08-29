package net.matsudamper.browser.data.forminput

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FormInputPreferenceTest {
    private lateinit var context: Context
    private lateinit var repository: FormInputRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FormInputDatabase.closeInstance()
        repository = FormInputRepository(context)
    }

    @After
    fun tearDown() {
        runBlocking { repository.deleteAll() }
        FormInputDatabase.closeInstance()
    }

    @Test
    fun disabledPathDoesNotSaveOrSuggest() = runBlocking {
        val page = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 443,
            path = "/form",
        )
        val origin = page.origin()
        repository.setPathEnabled(origin, "/form", enabled = false)
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "hello")),
        )

        assertTrue(repository.getSuggestions(pageKey = page, fieldKey = "comment").isEmpty())
        assertEquals(0, repository.observeSavedPathCount(origin).first())
    }

    @Test
    fun disabledFieldDoesNotSaveOrSuggest() = runBlocking {
        val page = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 443,
            path = "/form",
        )
        val origin = page.origin()
        repository.setFieldEnabled(origin, "/form", "comment", enabled = false)
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "hello")),
        )

        assertTrue(repository.getSuggestions(pageKey = page, fieldKey = "comment").isEmpty())
        assertTrue(repository.observeSavedFields(origin, "/form").first().isEmpty())
    }

    @Test
    fun deletePathRemovesSavedData() = runBlocking {
        val page = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 443,
            path = "/form",
        )
        val origin = page.origin()
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "hello")),
        )
        repository.deletePath(origin, "/form")

        assertEquals(0, repository.observeSavedPathCount(origin).first())
    }

    @Test
    fun deleteLastFieldRemovesPathPreference() = runBlocking {
        val page = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 443,
            path = "/form",
        )
        val origin = page.origin()
        repository.setPathEnabled(origin, "/form", enabled = false)
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "hello")),
        )
        repository.deleteField(origin, "/form", "comment")

        assertTrue(repository.getPathEnabled(origin, "/form"))
    }

    @Test
    fun observeSavedFieldsUpdatesWhenValueAdded() = runBlocking {
        val page = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 443,
            path = "/form",
        )
        val origin = page.origin()
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "first")),
        )
        assertEquals(
            1,
            repository.observeSavedFields(origin, "/form").first().single().valueCount,
        )
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "second")),
        )
        assertEquals(
            2,
            repository.observeSavedFields(origin, "/form").first().single().valueCount,
        )
    }

    @Test
    fun deleteValueRemovesSingleSavedValue() = runBlocking {
        val page = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 443,
            path = "/form",
        )
        val origin = page.origin()
        repository.saveFields(
            pageKey = page,
            fields = listOf(
                FormFieldEntry(fieldKey = "comment", value = "first"),
                FormFieldEntry(fieldKey = "comment", value = "second"),
            ),
        )
        repository.deleteValue(origin, "/form", "comment", "first")

        assertEquals(
            listOf("second"),
            repository.observeSavedValues(origin, "/form", "comment").first(),
        )
        assertEquals(
            1,
            repository.observeSavedFields(origin, "/form").first().single().valueCount,
        )
    }

    @Test
    fun preferencesAreIsolatedByOrigin() = runBlocking {
        val httpsOrigin = FormInputOrigin(scheme = "https", host = "example.com", port = 443)
        val httpOrigin = FormInputOrigin(scheme = "http", host = "example.com", port = 80)
        repository.setPathEnabled(httpsOrigin, "/form", enabled = false)
        repository.saveFields(
            pageKey = FormInputPageKey(
                scheme = "http",
                host = "example.com",
                port = 80,
                path = "/form",
            ),
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "plain")),
        )

        assertTrue(
            repository.getSuggestions(
                pageKey = FormInputPageKey(
                    scheme = "http",
                    host = "example.com",
                    port = 80,
                    path = "/form",
                ),
                fieldKey = "comment",
            ).isNotEmpty(),
        )
        assertTrue(
            repository.getSuggestions(
                pageKey = FormInputPageKey(
                    scheme = "https",
                    host = "example.com",
                    port = 443,
                    path = "/form",
                ),
                fieldKey = "comment",
            ).isEmpty(),
        )
    }

    @Test
    fun saveFieldsTouchesDuplicateValueAndCapsRowCount() = runBlocking {
        val page = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 443,
            path = "/form",
        )
        repeat(FormInputRepository.MAX_FIELD_VALUE_ROWS + 5) { index ->
            repository.saveFields(
                pageKey = page,
                fields = listOf(FormFieldEntry(fieldKey = "query", value = "value$index")),
            )
        }
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "query", value = "value0")),
        )
        assertEquals(
            listOf("value0"),
            repository.getSuggestions(pageKey = page, fieldKey = "query", limit = 1),
        )
        assertEquals(
            FormInputRepository.MAX_FIELD_VALUE_ROWS,
            FormInputDatabase.getInstance(context).formInputDao().countValueRowsForField(
                scheme = "https",
                host = "example.com",
                port = 443,
                path = "/form",
                fieldKey = "query",
            ),
        )
    }
}
