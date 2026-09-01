package net.matsudamper.browser.feature.addressautofill

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.mockk.coEvery
import io.mockk.mockk
import net.matsudamper.browser.data.address.AddressEntity
import net.matsudamper.browser.data.address.AddressRepository
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
@Suppress("NonAsciiCharacters")
class AddressAutofillCoordinatorTest {

    @Test
    fun フォーカス喪失後に候補バーが消える() = runTest {
        val env = createEnv()
        showNameSuggestions(env)

        env.coordinator.onFieldBlur()
        advanceTimeBy(ADDRESS_AUTOFILL_BLUR_HIDE_WAIT_MS - 1)
        runCurrent()
        assertTrue(env.host.isBarVisible)

        advanceTimeBy(1)
        runCurrent()
        assertFalse(env.host.isBarVisible)
        assertEquals(FIELD_KIND_OTHER, env.host.focusedAutofillKind)
    }

    @Test
    fun 遅延中に再フォーカスしたら候補バーは残る() = runTest {
        val env = createEnv()
        showNameSuggestions(env)

        env.coordinator.onFieldBlur()
        advanceTimeBy(ADDRESS_AUTOFILL_BLUR_HIDE_WAIT_MS / 2)
        runCurrent()
        assertTrue(env.host.isBarVisible)

        env.coordinator.onFieldFocus(FIELD_KIND_NAME)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        runCurrent()
        assertTrue(env.host.isBarVisible)

        advanceTimeBy(ADDRESS_AUTOFILL_BLUR_HIDE_WAIT_MS)
        runCurrent()
        assertTrue(env.host.isBarVisible)
        assertEquals(0, env.host.hideCount)
    }

    @Test
    fun フォーカス喪失後の住所取得では候補バーを出さない() = runTest {
        val env = createEnv()
        showNameSuggestions(env)

        env.coordinator.onFieldBlur()
        env.coordinator.onAddressFetch(1)
        advanceTimeBy(ADDRESS_AUTOFILL_BLUR_HIDE_WAIT_MS)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        runCurrent()
        assertFalse(env.host.isBarVisible)
    }

    @Test
    fun メールのない住所ではメール欄の候補バーを閉じる() = runTest {
        val env = createEnv(
            address = SAMPLE_ADDRESS.copy(email = ""),
        )
        showNameSuggestions(env)

        env.coordinator.onFieldFocus(FIELD_KIND_EMAIL)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertFalse(env.host.isBarVisible)
    }

    @Test
    fun フォーカス中ポート切断で候補バーを閉じる() = runTest {
        val env = createEnv()
        showNameSuggestions(env)

        env.coordinator.onFocusPortDisconnected()
        runCurrent()
        assertFalse(env.host.isBarVisible)
        assertEquals(FIELD_KIND_OTHER, env.host.focusedAutofillKind)
    }

    @Test
    fun フォーカス未確定の住所取得はフォールバックとして候補バーを出す() = runTest {
        val env = createEnv()

        env.coordinator.onAddressFetch(1)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertTrue(env.host.isBarVisible)
    }

    @Test
    fun 非住所欄フォーカス後の住所取得では候補バーを出さない() = runTest {
        val env = createEnv()
        env.coordinator.onFieldFocus(FIELD_KIND_OTHER)

        env.coordinator.onAddressFetch(1)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertFalse(env.host.isBarVisible)
    }

    @Test
    fun 非住所欄フォーカスでは候補バーを即閉じる() = runTest {
        val env = createEnv()
        showNameSuggestions(env)

        env.coordinator.onFieldFocus(FIELD_KIND_OTHER)
        runCurrent()
        assertFalse(env.host.isBarVisible)
        assertEquals(FIELD_KIND_OTHER, env.host.focusedAutofillKind)
    }

    private fun TestScope.createEnv(
        address: AddressEntity = SAMPLE_ADDRESS,
    ): TestEnv {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val host = FakeHost(this)
        val repository = mockk<AddressRepository>()
        coEvery { repository.getAll() } returns listOf(address)
        val coordinator = AddressAutofillCoordinator(
            fillExtension = mockk(relaxed = true),
            ioDispatcher = dispatcher,
        )
        coordinator.attach(
            session = mockk<GeckoSession>(relaxed = true),
            host = host,
            addressRepository = repository,
        )
        return TestEnv(coordinator, host)
    }

    private fun TestScope.showNameSuggestions(env: TestEnv) {
        env.coordinator.onFieldFocus(FIELD_KIND_NAME)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertTrue(env.host.isBarVisible)
        assertEquals(0, env.host.hideCount)
    }

    private class TestEnv(
        val coordinator: AddressAutofillCoordinator,
        val host: FakeHost,
    )

    private class FakeHost(
        override val coroutineScope: CoroutineScope,
    ) : AddressAutofillHost {
        override var focusedAutofillKind: String? = null
        override var onAddressSelectOptions:
            ((List<Autocomplete.AddressSelectOption>) -> Unit)? = null
        override var autofillBarHideGeneration: Int = 0
        var shownItems: List<AddressAutofillSuggestionItem>? = null
            private set
        var hideCount: Int = 0
            private set
        val isBarVisible: Boolean
            get() = shownItems != null

        override fun showAddressAutofillBar(items: List<AddressAutofillSuggestionItem>) {
            shownItems = items
        }

        override fun hideAddressAutofillBar() {
            hideCount += 1
            shownItems = null
        }
    }

    private companion object {
        val SAMPLE_ADDRESS = AddressEntity(
            givenName = "Taro",
            familyName = "Yamada",
            email = "taro@example.com",
            streetAddress = "千代田1-1",
            postalCode = "100-0001",
        )
    }
}
