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

        env.coordinator.onFieldBlur(env.session)
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

        env.coordinator.onFieldBlur(env.session)
        advanceTimeBy(ADDRESS_AUTOFILL_BLUR_HIDE_WAIT_MS / 2)
        runCurrent()
        assertTrue(env.host.isBarVisible)

        env.coordinator.onFieldFocus(env.session, FIELD_KIND_NAME)
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

        env.coordinator.onFieldBlur(env.session)
        env.coordinator.fetchAddresses(1)
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

        env.coordinator.onFieldFocus(env.session, FIELD_KIND_EMAIL)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertFalse(env.host.isBarVisible)
    }

    @Test
    fun フォーカス中ポート切断で候補バーを閉じる() = runTest {
        val env = createEnv()
        showNameSuggestions(env)

        env.coordinator.onFocusPortDisconnected(env.session)
        runCurrent()
        assertFalse(env.host.isBarVisible)
        assertEquals(FIELD_KIND_OTHER, env.host.focusedAutofillKind)
    }

    @Test
    fun フォーカス未確定の住所取得はフォールバックとして候補バーを出す() = runTest {
        val env = createEnv()

        env.coordinator.fetchAddresses(1)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertTrue(env.host.isBarVisible)
    }

    @Test
    fun 非住所欄フォーカス後の住所取得では候補バーを出さない() = runTest {
        val env = createEnv()
        env.coordinator.onFieldFocus(env.session, FIELD_KIND_OTHER)

        env.coordinator.fetchAddresses(1)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertFalse(env.host.isBarVisible)
    }

    @Test
    fun 非住所欄フォーカスでは候補バーを即閉じる() = runTest {
        val env = createEnv()
        showNameSuggestions(env)

        env.coordinator.onFieldFocus(env.session, FIELD_KIND_OTHER)
        runCurrent()
        assertFalse(env.host.isBarVisible)
        assertEquals(FIELD_KIND_OTHER, env.host.focusedAutofillKind)
    }

    @Test
    fun 別画面を接続しても元の画面に候補バーを出す() = runTest {
        val env = createEnv()
        val other = attachSurface(env)

        env.coordinator.onFieldFocus(env.session, FIELD_KIND_EMAIL)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertTrue(env.host.isBarVisible)
        assertFalse(other.host.isBarVisible)
    }

    @Test
    fun 新しく接続した画面の住所取得は新しい画面へ届く() = runTest {
        val env = createEnv()
        env.coordinator.onFieldFocus(env.session, FIELD_KIND_OTHER)
        val opened = attachSurface(env)

        env.coordinator.fetchAddresses(1)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertTrue(opened.host.isBarVisible)
        assertFalse(env.host.isBarVisible)
    }

    @Test
    fun 直近フォーカスの画面を閉じたら次に古いフォーカス先へ届く() = runTest {
        val env = createEnv()
        val later = attachSurface(env)
        val closing = attachSurface(env)
        env.coordinator.onWindowFocusChanged(env.session, true)
        env.coordinator.onFieldFocus(env.session, FIELD_KIND_NAME)
        env.coordinator.onWindowFocusChanged(closing.session, true)
        env.coordinator.onFieldFocus(closing.session, FIELD_KIND_NAME)
        advanceUntilIdle()
        env.coordinator.detach(closing.session)

        env.coordinator.fetchAddresses(1)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertTrue(env.host.isBarVisible)
        assertFalse(later.host.isBarVisible)
    }

    @Test
    fun 住所取得の待機中にフォーカスが来たら古い宛先には出さない() = runTest {
        val env = createEnv()
        env.coordinator.onWindowFocusChanged(env.session, true)
        val opened = attachSurface(env)

        env.coordinator.fetchAddresses(1)
        env.coordinator.onFieldFocus(env.session, FIELD_KIND_NAME)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertTrue(env.host.isBarVisible)
        assertFalse(opened.host.isBarVisible)
    }

    @Test
    fun 前面へ戻った画面が住所取得の宛先になる() = runTest {
        val env = createEnv()
        val background = attachSurface(env)

        env.coordinator.onWindowFocusChanged(env.session, true)
        env.coordinator.fetchAddresses(1)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertTrue(env.host.isBarVisible)
        assertFalse(background.host.isBarVisible)
    }

    @Test
    fun 背面の画面のフォーカスによる住所取得は前面へ出さない() = runTest {
        val env = createEnv()
        val background = attachSurface(env)
        env.coordinator.onWindowFocusChanged(env.session, true)

        env.coordinator.onFieldFocus(background.session, FIELD_KIND_NAME)
        env.coordinator.fetchAddresses(1)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertFalse(env.host.isBarVisible)
        assertTrue(background.host.isBarVisible)
    }

    @Test
    fun 前面が別画面へ移ったら古いフォーカスで住所取得を捨てない() = runTest {
        val env = createEnv()
        env.coordinator.onWindowFocusChanged(env.session, true)
        env.coordinator.onFieldFocus(env.session, FIELD_KIND_NAME)
        val opened = attachSurface(env)
        env.coordinator.onWindowFocusChanged(opened.session, true)

        env.coordinator.fetchAddresses(1)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertTrue(opened.host.isBarVisible)
    }

    @Test
    fun 背面の画面の非住所欄フォーカスによる住所取得も前面へ出さない() = runTest {
        val env = createEnv()
        val background = attachSurface(env)
        env.coordinator.onWindowFocusChanged(env.session, true)

        env.coordinator.onFieldFocus(background.session, FIELD_KIND_OTHER)
        env.coordinator.fetchAddresses(1)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertFalse(env.host.isBarVisible)
    }

    @Test
    fun 読み出し中に前面が変わったら住所取得を新しい前面へ出さない() = runTest {
        val env = createEnv()
        env.coordinator.onWindowFocusChanged(env.session, true)
        val request = env.coordinator.onAddressFetchStarted()

        val opened = attachSurface(env)
        env.coordinator.onWindowFocusChanged(opened.session, true)
        env.coordinator.onAddressFetch(request, 1)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertFalse(opened.host.isBarVisible)
    }

    @Test
    fun 待機中に届いた背面のフォーカス通知で住所取得を前面へ出さない() = runTest {
        val env = createEnv()
        val background = attachSurface(env)
        env.coordinator.onWindowFocusChanged(env.session, true)

        env.coordinator.fetchAddresses(1)
        env.coordinator.onFieldFocus(background.session, FIELD_KIND_NAME)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertFalse(env.host.isBarVisible)
    }

    /** 住所取得は要求と完了の2段階で届く。 */
    private fun AddressAutofillCoordinator.fetchAddresses(count: Int) {
        onAddressFetch(onAddressFetchStarted(), count)
    }

    @Test
    fun 先行する住所取得の完了で後続の要求が捨てられない() = runTest {
        val env = createEnv()
        env.coordinator.onWindowFocusChanged(env.session, true)
        val first = env.coordinator.onAddressFetchStarted()
        val second = env.coordinator.onAddressFetchStarted()

        env.coordinator.onAddressFetch(first, 1)
        advanceUntilIdle()
        env.host.hideAddressAutofillBar()
        env.coordinator.onAddressFetch(second, 1)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertTrue(env.host.isBarVisible)
    }

    private fun TestScope.attachSurface(env: TestEnv): Surface {
        val host = FakeHost(this)
        val session = mockk<GeckoSession>(relaxed = true)
        env.coordinator.attach(
            session = session,
            host = host,
            addressRepository = env.repository,
        )
        return Surface(host, session)
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
        val session = mockk<GeckoSession>(relaxed = true)
        coordinator.attach(
            session = session,
            host = host,
            addressRepository = repository,
        )
        return TestEnv(coordinator, host, session, repository)
    }

    private fun TestScope.showNameSuggestions(env: TestEnv) {
        env.coordinator.onFieldFocus(env.session, FIELD_KIND_NAME)
        advanceTimeBy(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
        advanceUntilIdle()
        assertTrue(env.host.isBarVisible)
        assertEquals(0, env.host.hideCount)
    }

    private class Surface(
        val host: FakeHost,
        val session: GeckoSession,
    )

    private class TestEnv(
        val coordinator: AddressAutofillCoordinator,
        val host: FakeHost,
        val session: GeckoSession,
        val repository: AddressRepository,
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
