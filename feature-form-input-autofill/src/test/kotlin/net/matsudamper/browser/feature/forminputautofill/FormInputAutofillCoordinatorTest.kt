package net.matsudamper.browser.feature.forminputautofill

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.matsudamper.browser.data.forminput.FormFieldEntry
import net.matsudamper.browser.data.forminput.FormInputPageKey
import net.matsudamper.browser.data.forminput.FormInputRepository
import net.matsudamper.browser.feature.addressautofill.AddressAutofillSuggestionItem
import net.matsudamper.browser.feature.addressautofill.AddressAutofillSuggestionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoSession
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FormInputAutofillCoordinatorTest {
    private val imeReadyWaitMs = 150L
    private val blurHideWaitMs = 300L

    @Test
    fun fieldFocusShowsSavedSuggestions() = runTest {
        val env = createEnv(this)
        env.extension.dispatchFieldFocus(env.session, "comment", "https://example.com/form")
        advanceTimeBy(imeReadyWaitMs)
        runCurrent()

        assertTrue(env.host.isBarVisible)
        assertEquals(1, env.host.shownItems.size)
        assertEquals("saved value", env.host.shownItems.first().label)
        assertEquals(AddressAutofillSuggestionKind.FormField, env.host.shownItems.first().kind)
    }

    @Test
    fun formSubmitSavesFields() = runTest {
        val env = createEnv(this)
        env.extension.dispatchFormSubmit(
            env.session,
            "https://example.com/form",
            listOf(FormInputFieldMessage(fieldKey = "comment", value = "hello")),
        )
        runCurrent()

        coVerify {
            env.repository.saveFields(
                pageKey = FormInputPageKey(
                    scheme = "https",
                    host = "example.com",
                    port = 443,
                    path = "/form",
                ),
                fields = listOf(FormFieldEntry(fieldKey = "comment", value = "hello")),
            )
        }
    }

    @Test
    fun fieldLongPressShowsSaveDialog() = runTest {
        val env = createEnv(this)
        coEvery {
            env.repository.getFieldEnabled(any(), any(), "comment")
        } returns false
        coEvery {
            env.repository.getFieldEnabled(any(), any(), "title")
        } returns true

        env.extension.dispatchFieldLongPress(
            env.session,
            fieldKey = "comment",
            pageUrl = "https://example.com/form",
            fields = listOf(
                FormInputFieldMessage(fieldKey = "comment", value = "hello"),
                FormInputFieldMessage(fieldKey = "title", value = "topic"),
            ),
        )
        runCurrent()

        assertEquals(2, env.host.saveDialogRequest?.fields?.size)
        assertTrue(env.host.saveDialogRequest?.fields?.any { it.fieldKey == "comment" && it.initiallySelected } == true)
        assertTrue(env.host.saveDialogRequest?.fields?.any { it.fieldKey == "title" && it.initiallySelected } == true)
    }

    @Test
    fun fieldLongPressDeduplicatesDuplicateFieldKeys() = runTest {
        val env = createEnv(this)
        coEvery { env.repository.getFieldEnabled(any(), any(), "comment") } returns false

        env.extension.dispatchFieldLongPress(
            env.session,
            fieldKey = "comment",
            pageUrl = "https://example.com/form",
            fields = listOf(
                FormInputFieldMessage(fieldKey = "comment", value = "first"),
                FormInputFieldMessage(fieldKey = "comment", value = "second"),
            ),
        )
        runCurrent()

        assertEquals(1, env.host.saveDialogRequest?.fields?.size)
        assertEquals("first", env.host.saveDialogRequest?.fields?.single()?.value)
    }

    @Test
    fun fieldBlurHidesBar() = runTest {
        val env = createEnv(this)
        env.extension.dispatchFieldFocus(env.session, "comment", "https://example.com/form")
        advanceTimeBy(imeReadyWaitMs)
        runCurrent()
        assertTrue(env.host.isBarVisible)

        env.extension.dispatchFieldBlur(env.session)
        advanceTimeBy(blurHideWaitMs)
        runCurrent()
        assertFalse(env.host.isBarVisible)
    }

    @Test
    fun detachDoesNotBreakOtherSessionListener() = runTest {
        val dispatcher = StandardTestDispatcher(this.testScheduler)
        Dispatchers.setMain(dispatcher)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val repository = mockk<FormInputRepository>(relaxed = true)
        val extension = FormInputAutofillWebExtension()
        val mainHost = RecordingHost(scope)
        val popupHost = RecordingHost(scope)
        val mainCoordinator = FormInputAutofillCoordinator(
            fillExtension = extension,
            ioDispatcher = dispatcher,
        )
        val popupCoordinator = FormInputAutofillCoordinator(
            fillExtension = extension,
            ioDispatcher = dispatcher,
        )
        val mainSession = GeckoSession()
        val popupSession = GeckoSession()
        coEvery {
            repository.getSuggestions(
                pageKey = FormInputPageKey(
                    scheme = "https",
                    host = "example.com",
                    port = 443,
                    path = "/form",
                ),
                fieldKey = "comment",
            )
        } returns listOf("saved value")

        mainCoordinator.attach(mainSession, mainHost, repository)
        popupCoordinator.attach(popupSession, popupHost, repository)
        popupCoordinator.detach(popupSession)

        extension.dispatchFieldFocus(mainSession, "comment", "https://example.com/form")
        advanceTimeBy(imeReadyWaitMs)
        runCurrent()

        assertTrue(mainHost.isBarVisible)
    }

    private fun createEnv(scope: TestScope): TestEnv {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        Dispatchers.setMain(dispatcher)
        val coroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
        val repository = mockk<FormInputRepository>(relaxed = true)
        coEvery {
            repository.getSuggestions(
                pageKey = FormInputPageKey(
                    scheme = "https",
                    host = "example.com",
                    port = 443,
                    path = "/form",
                ),
                fieldKey = "comment",
            )
        } returns listOf("saved value")
        val extension = FormInputAutofillWebExtension()
        val host = RecordingHost(coroutineScope)
        val coordinator = FormInputAutofillCoordinator(
            fillExtension = extension,
            ioDispatcher = dispatcher,
        )
        val session = GeckoSession()
        coordinator.attach(session, host, repository)
        return TestEnv(extension, session, host, repository, coordinator)
    }

    private class TestEnv(
        val extension: FormInputAutofillWebExtension,
        val session: GeckoSession,
        val host: RecordingHost,
        val repository: FormInputRepository,
        val coordinator: FormInputAutofillCoordinator,
    )

    private class RecordingHost(
        override val coroutineScope: CoroutineScope,
    ) : FormInputAutofillHost {
        override var focusedAutofillKind: String? = null
        override var onAddressSelectOptions: ((List<Autocomplete.AddressSelectOption>) -> Unit)? = null
        override var autofillBarHideGeneration: Int = 0
        var shownItems: List<AddressAutofillSuggestionItem> = emptyList()
        var isBarVisible: Boolean = false
        var saveDialogRequest: FormInputSaveDialogRequest? = null

        override fun showAddressAutofillBar(items: List<AddressAutofillSuggestionItem>) {
            shownItems = items
            isBarVisible = true
        }

        override fun hideAddressAutofillBar() {
            shownItems = emptyList()
            isBarVisible = false
        }

        override fun showFormInputSaveDialog(request: FormInputSaveDialogRequest) {
            saveDialogRequest = request
        }

        override fun dismissFormInputSaveDialog() {
            saveDialogRequest = null
        }
    }
}
