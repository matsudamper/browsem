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
        val page = FormInputPageKey(host = "example.com", path = "/form")
        repository.setPathEnabled("example.com", "/form", enabled = false)
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "hello")),
        )

        assertTrue(repository.getSuggestions(pageKey = page, fieldKey = "comment").isEmpty())
        assertEquals(0, repository.observeSavedPathCount("example.com").first())
    }

    @Test
    fun disabledFieldDoesNotSaveOrSuggest() = runBlocking {
        val page = FormInputPageKey(host = "example.com", path = "/form")
        repository.setFieldEnabled("example.com", "/form", "comment", enabled = false)
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "hello")),
        )

        assertTrue(repository.getSuggestions(pageKey = page, fieldKey = "comment").isEmpty())
        assertTrue(repository.observeSavedFields("example.com", "/form").first().isEmpty())
    }

    @Test
    fun deletePathRemovesSavedData() = runBlocking {
        val page = FormInputPageKey(host = "example.com", path = "/form")
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "hello")),
        )
        repository.deletePath("example.com", "/form")

        assertEquals(0, repository.observeSavedPathCount("example.com").first())
    }

    @Test
    fun deleteLastFieldRemovesPathPreference() = runBlocking {
        val page = FormInputPageKey(host = "example.com", path = "/form")
        repository.setPathEnabled("example.com", "/form", enabled = false)
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "hello")),
        )
        repository.deleteField("example.com", "/form", "comment")

        assertTrue(repository.getPathEnabled("example.com", "/form"))
    }

    @Test
    fun observeSavedFieldsUpdatesWhenValueAdded() = runBlocking {
        val page = FormInputPageKey(host = "example.com", path = "/form")
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "first")),
        )
        assertEquals(
            listOf("first"),
            repository.observeSavedFields("example.com", "/form").first().single().values,
        )
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "second")),
        )
        assertEquals(
            listOf("second", "first"),
            repository.observeSavedFields("example.com", "/form").first().single().values,
        )
    }
}
