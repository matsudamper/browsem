package net.matsudamper.browser.feature.addressautofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.Autocomplete
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AddressAutofillMappingTest {

    private val address = Autocomplete.Address.Builder()
        .familyName("YamadaFillTest")
        .givenName("TaroFillTest")
        .email("fill-test@example.com")
        .build()

    @Test
    fun mdnLastNameIdIsAddressField() {
        assertTrue(isAddressAutofillField(mapOf("id" to "lastName")))
    }

    @Test
    fun familyNameAutocompleteIsAddressField() {
        assertTrue(
            isAddressAutofillField(mapOf("autocomplete" to "shipping family-name")),
        )
    }

    @Test
    fun usernameIsNotAddressField() {
        assertFalse(isAddressAutofillField(mapOf("id" to "userName")))
        assertFalse(isAddressAutofillField(mapOf("autocomplete" to "username")))
    }

    @Test
    fun emailFieldIsNotAddressField() {
        assertFalse(isAddressAutofillField(mapOf("id" to "email", "type" to "email")))
        assertFalse(isAddressAutofillField(mapOf("autocomplete" to "email")))
        assertFalse(isAddressAutofillField(mapOf("id" to "email", "autocomplete" to "off", "type" to "email")))
    }

    @Test
    fun emailFieldIsDetectedEvenWhenAutocompleteIsOff() {
        assertTrue(
            isEmailAutofillField(
                mapOf("id" to "email", "name" to "email", "type" to "email", "autocomplete" to "off"),
            ),
        )
        assertTrue(isEmailAutofillField(mapOf("type" to "email")))
        assertTrue(isEmailAutofillField(mapOf("autocomplete" to "email")))
    }

    @Test
    fun addressLine1GetsStreetAddressButLaterLinesDoNot() {
        val withStreet = Autocomplete.Address.Builder()
            .streetAddress("東京都渋谷区1-2-3")
            .build()
        assertEquals(
            "東京都渋谷区1-2-3",
            resolveAddressAutofillValue(mapOf("autocomplete" to "street-address"), withStreet),
        )
        assertEquals(
            "東京都渋谷区1-2-3",
            resolveAddressAutofillValue(mapOf("autocomplete" to "address-line1"), withStreet),
        )
        assertNull(
            resolveAddressAutofillValue(mapOf("autocomplete" to "address-line2"), withStreet),
        )
        assertNull(
            resolveAddressAutofillValue(mapOf("autocomplete" to "address-line3"), withStreet),
        )
    }

    @Test
    fun lastNameIdMapsToFamilyName() {
        assertEquals(
            "YamadaFillTest",
            resolveAddressAutofillValue(mapOf("id" to "lastName"), address),
        )
    }

    @Test
    fun addressFillDoesNotWriteEmailField() {
        assertNull(
            resolveAddressAutofillValue(
                mapOf("id" to "email", "type" to "email", "autocomplete" to "off"),
                address,
                AddressAutofillFillMode.Address,
            ),
        )
        assertNull(
            resolveAddressAutofillValue(
                mapOf("autocomplete" to "email"),
                address,
                AddressAutofillFillMode.Address,
            ),
        )
    }

    @Test
    fun emailFillWritesOnlyEmailField() {
        assertEquals(
            "fill-test@example.com",
            resolveAddressAutofillValue(
                mapOf("id" to "email", "type" to "email", "autocomplete" to "off"),
                address,
                AddressAutofillFillMode.Email,
            ),
        )
        assertNull(
            resolveAddressAutofillValue(
                mapOf("id" to "lastName", "autocomplete" to "family-name"),
                address,
                AddressAutofillFillMode.Email,
            ),
        )
        assertNull(
            resolveAddressAutofillValue(
                mapOf("id" to "firstName", "autocomplete" to "given-name"),
                address,
                AddressAutofillFillMode.Email,
            ),
        )
    }

    @Test
    fun lastNameIdIsNameField() {
        assertTrue(isNameAutofillField(mapOf("id" to "lastName")))
        assertTrue(isNameAutofillField(mapOf("autocomplete" to "shipping family-name")))
        assertFalse(isNameAutofillField(mapOf("autocomplete" to "street-address")))
        assertFalse(isNameAutofillField(mapOf("id" to "userName")))
    }

    @Test
    fun addressCompletionTextUsesFieldKind() {
        val withAddress = Autocomplete.Address.Builder()
            .familyName("YamadaFillTest")
            .givenName("TaroFillTest")
            .email("fill-test@example.com")
            .postalCode("100-0001")
            .addressLevel1("東京都")
            .addressLevel2("千代田区")
            .streetAddress("千代田1-1")
            .build()
        assertEquals(
            "YamadaFillTest TaroFillTest",
            addressCompletionText(withAddress, AddressAutofillSuggestionKind.Name),
        )
        assertEquals(
            "〒100-0001 東京都 千代田区 千代田1-1",
            addressCompletionText(withAddress, AddressAutofillSuggestionKind.Address),
        )
        assertEquals(
            "fill-test@example.com",
            addressCompletionText(withAddress, AddressAutofillSuggestionKind.Email),
        )
    }

    @Test
    fun withoutEmailClearsEmailOnly() {
        val stripped = address.withoutEmail()
        assertEquals("YamadaFillTest", stripped.familyName)
        assertEquals("TaroFillTest", stripped.givenName)
        assertEquals("", stripped.email)
    }

    @Test
    fun addressFillMessageDoesNotIncludeEmail() {
        val message = address.toFillMessage(AddressAutofillFillMode.Address)
        assertEquals("address", message.getString("mode"))
        assertEquals("", message.getJSONObject("address").getString("email"))
        assertEquals("YamadaFillTest", message.getJSONObject("address").getString("familyName"))
    }

    @Test
    fun emailFillMessageIncludesOnlyEmailValue() {
        val message = address.toFillMessage(AddressAutofillFillMode.Email)
        assertEquals("email", message.getString("mode"))
        assertEquals("fill-test@example.com", message.getJSONObject("address").getString("email"))
    }
}
