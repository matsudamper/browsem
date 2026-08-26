package net.matsudamper.browser.data.address

fun AddressEntity.displayName(): String {
    val name = listOf(familyName, givenName).filter { it.isNotEmpty() }.joinToString(" ")
    return name.ifEmpty { organization }
}

fun AddressEntity.displayText(): String = buildList {
    if (postalCode.isNotEmpty()) add("〒$postalCode")
    if (addressLevel1.isNotEmpty()) add(addressLevel1)
    if (addressLevel2.isNotEmpty()) add(addressLevel2)
    if (addressLevel3.isNotEmpty()) add(addressLevel3)
    if (streetAddress.isNotEmpty()) add(streetAddress)
    if (tel.isNotEmpty()) add(tel)
    if (email.isNotEmpty()) add(email)
}.joinToString(" ")
