package net.matsudamper.browser.data.forminput

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FormInputRepositoryTest {
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
    fun suggestionsMatchHostAndPathExactly() = runBlocking {
        val page = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 443,
            path = "/form",
        )
        val otherPath = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 443,
            path = "/other",
        )
        repository.registerField(page.origin(), page.path, "comment")
        repository.registerField(otherPath.origin(), otherPath.path, "comment")
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "hello")),
        )
        repository.saveFields(
            pageKey = otherPath,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "other")),
        )

        assertEquals(
            listOf("hello"),
            repository.getSuggestions(pageKey = page, fieldKey = "comment"),
        )
        assertEquals(
            listOf("other"),
            repository.getSuggestions(pageKey = otherPath, fieldKey = "comment"),
        )
    }

    @Test
    fun suggestionsAreDistinctAndOrderedByRecency() = runBlocking {
        val page = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 443,
            path = "/form",
        )
        repository.registerField(page.origin(), page.path, "title")
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "title", value = "first")),
        )
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "title", value = "second")),
        )
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "title", value = "first")),
        )

        assertEquals(
            listOf("first", "second"),
            repository.getSuggestions(pageKey = page, fieldKey = "title"),
        )
    }

    @Test
    fun differentOriginsDoNotShareSuggestions() = runBlocking {
        val httpsPage = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 443,
            path = "/form",
        )
        val httpPage = FormInputPageKey(
            scheme = "http",
            host = "example.com",
            port = 80,
            path = "/form",
        )
        val customPortPage = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 8443,
            path = "/form",
        )
        repository.registerField(httpsPage.origin(), httpsPage.path, "comment")
        repository.registerField(httpPage.origin(), httpPage.path, "comment")
        repository.registerField(customPortPage.origin(), customPortPage.path, "comment")
        repository.saveFields(
            pageKey = httpsPage,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "secure")),
        )
        repository.saveFields(
            pageKey = httpPage,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "plain")),
        )
        repository.saveFields(
            pageKey = customPortPage,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "custom")),
        )

        assertEquals(
            listOf("secure"),
            repository.getSuggestions(pageKey = httpsPage, fieldKey = "comment"),
        )
        assertEquals(
            listOf("plain"),
            repository.getSuggestions(pageKey = httpPage, fieldKey = "comment"),
        )
        assertEquals(
            listOf("custom"),
            repository.getSuggestions(pageKey = customPortPage, fieldKey = "comment"),
        )
    }

    @Test
    fun unregisteredFieldDoesNotSaveOrSuggest() = runBlocking {
        val page = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 443,
            path = "/form",
        )
        repository.saveFields(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "hello")),
        )

        assertTrue(repository.getSuggestions(pageKey = page, fieldKey = "comment").isEmpty())
    }

    @Test
    fun registerFieldAndSaveRegistersAndPersistsValues() = runBlocking {
        val page = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 443,
            path = "/form",
        )
        repository.registerFieldAndSave(
            pageKey = page,
            fields = listOf(
                FormFieldEntry(fieldKey = "comment", value = "hello"),
                FormFieldEntry(fieldKey = "title", value = ""),
            ),
        )

        assertEquals(listOf("hello"), repository.getSuggestions(pageKey = page, fieldKey = "comment"))
        assertTrue(repository.isFieldRegistered(page.origin(), page.path, "comment"))
        assertTrue(repository.isFieldRegistered(page.origin(), page.path, "title"))
    }

    @Test
    fun registerFieldAndSaveRecordsFieldWhenPathDisabled() = runBlocking {
        val page = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 443,
            path = "/form",
        )
        repository.setPathEnabled(page.origin(), page.path, enabled = false)
        repository.registerFieldAndSave(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "hello")),
        )

        assertTrue(repository.isFieldRegistered(page.origin(), page.path, "comment"))
        assertTrue(repository.getSuggestions(pageKey = page, fieldKey = "comment").isEmpty())
    }

    @Test
    fun deleteFieldRemovesRegistration() = runBlocking {
        val page = FormInputPageKey(
            scheme = "https",
            host = "example.com",
            port = 443,
            path = "/form",
        )
        repository.registerFieldAndSave(
            pageKey = page,
            fields = listOf(FormFieldEntry(fieldKey = "comment", value = "hello")),
        )

        repository.deleteField(page.origin(), page.path, "comment")

        assertFalse(repository.isFieldRegistered(page.origin(), page.path, "comment"))
        assertTrue(repository.getSuggestions(pageKey = page, fieldKey = "comment").isEmpty())
    }
}
