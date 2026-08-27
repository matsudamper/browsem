package net.matsudamper.browser.feature.addressautofill

import net.matsudamper.browser.data.address.AddressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.Autocomplete

class AutocompleteAddressMappingTest {

    @Test
    fun toGeckoAddressSetsNameRequiredByGeckoValidityCheck() {
        val gecko = AddressEntity(
            givenName = "Peter",
            familyName = "Parker",
            streetAddress = "20 Ingram Street",
            postalCode = "11375",
            country = "US",
        ).toGeckoAddress()

        assertEquals("Peter Parker", gecko.name)
        assertEquals("Peter", gecko.givenName)
        assertEquals("Parker", gecko.familyName)
        assertTrue(gecko.name.isNotEmpty())
        assertTrue(gecko.streetAddress.isNotEmpty())
        assertTrue(gecko.postalCode.isNotEmpty())
    }

    @Test
    fun toGeckoFullNameUsesFamilyNameWhenGivenNameIsEmpty() {
        assertEquals(
            "山田",
            AddressEntity(familyName = "山田").toGeckoFullName(),
        )
    }

    @Test
    fun toGeckoCountryFallsBackWhenBlank() {
        val gecko = AddressEntity(
            givenName = "a",
            familyName = "b",
            streetAddress = "p",
            postalCode = "2222222",
        ).toGeckoAddress()

        assertTrue(gecko.country.isNotBlank())
    }

    @Test
    fun toEntityFallsBackToNameWhenComponentsAreBlank() {
        val entity = Autocomplete.Address.Builder()
            .name("山田 太郎")
            .build()
            .toEntity()
        assertEquals("山田 太郎", entity.givenName)
        assertEquals("", entity.familyName)
        assertEquals("", entity.additionalName)
    }
}
