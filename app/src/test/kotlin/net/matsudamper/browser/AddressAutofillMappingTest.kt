package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.Autocomplete

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
