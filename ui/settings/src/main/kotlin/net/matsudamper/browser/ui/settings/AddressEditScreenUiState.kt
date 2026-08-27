package net.matsudamper.browser.ui.settings

import androidx.compose.runtime.Stable

@Stable
data class AddressEditScreenUiState(
    val callbacks: Callbacks,
    val isNew: Boolean,
    val isLoading: Boolean,
    val givenName: String,
    val additionalName: String,
    val familyName: String,
    val organization: String,
    val streetAddress: String,
    val addressLevel1: String,
    val addressLevel2: String,
    val addressLevel3: String,
    val postalCode: String,
    val country: String,
    val tel: String,
    val email: String,
    val canSave: Boolean,
) {
    interface Callbacks {
        fun onGivenNameChange(value: String)
        fun onAdditionalNameChange(value: String)
        fun onFamilyNameChange(value: String)
        fun onOrganizationChange(value: String)
        fun onStreetAddressChange(value: String)
        fun onAddressLevel1Change(value: String)
        fun onAddressLevel2Change(value: String)
        fun onAddressLevel3Change(value: String)
        fun onPostalCodeChange(value: String)
        fun onCountryChange(value: String)
        fun onTelChange(value: String)
        fun onEmailChange(value: String)
        fun onSave()
    }
}
