package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.Autocomplete

class AddressAutofillMappingTest {

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
    fun lastNameIdMapsToFamilyName() {
        val address = Autocomplete.Address.Builder()
            .familyName("b")
            .givenName("a")
            .build()
        assertEquals("b", resolveAddressAutofillValue(mapOf("id" to "lastName"), address))
    }
}
