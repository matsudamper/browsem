package net.matsudamper.browser.screen.addresses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.address.AddressEntity
import net.matsudamper.browser.data.address.AddressRepository
import net.matsudamper.browser.ui.settings.address.AddressEditScreenUiState

internal class AddressEditScreenViewModel(
    private val addressRepository: AddressRepository,
    private val addressId: Long,
) : ViewModel() {

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    private val viewModelStateFlow = MutableStateFlow(
        ViewModelState(isLoading = addressId != AddressesScreenViewModel.NEW_ADDRESS_ID),
    )

    private val callbacks = object : AddressEditScreenUiState.Callbacks {
        override fun onGivenNameChange(value: String) {
            viewModelStateFlow.update { it.copy(givenName = value) }
        }

        override fun onAdditionalNameChange(value: String) {
            viewModelStateFlow.update { it.copy(additionalName = value) }
        }

        override fun onFamilyNameChange(value: String) {
            viewModelStateFlow.update { it.copy(familyName = value) }
        }

        override fun onOrganizationChange(value: String) {
            viewModelStateFlow.update { it.copy(organization = value) }
        }

        override fun onStreetAddressChange(value: String) {
            viewModelStateFlow.update { it.copy(streetAddress = value) }
        }

        override fun onAddressLevel1Change(value: String) {
            viewModelStateFlow.update { it.copy(addressLevel1 = value) }
        }

        override fun onAddressLevel2Change(value: String) {
            viewModelStateFlow.update { it.copy(addressLevel2 = value) }
        }

        override fun onAddressLevel3Change(value: String) {
            viewModelStateFlow.update { it.copy(addressLevel3 = value) }
        }

        override fun onPostalCodeChange(value: String) {
            viewModelStateFlow.update { it.copy(postalCode = value) }
        }

        override fun onCountryChange(value: String) {
            viewModelStateFlow.update { it.copy(country = value) }
        }

        override fun onTelChange(value: String) {
            viewModelStateFlow.update { it.copy(tel = value) }
        }

        override fun onEmailChange(value: String) {
            viewModelStateFlow.update { it.copy(email = value) }
        }

        override fun onSave() {
            val previous = viewModelStateFlow.getAndUpdate { current ->
                if (!current.canSave) current else current.copy(isSaving = true)
            }
            if (!previous.canSave) return
            viewModelScope.launch {
                runCatching {
                    addressRepository.save(
                        AddressEntity(
                            id = previous.id,
                            givenName = previous.givenName,
                            additionalName = previous.additionalName,
                            familyName = previous.familyName,
                            organization = previous.organization,
                            streetAddress = previous.streetAddress,
                            addressLevel1 = previous.addressLevel1,
                            addressLevel2 = previous.addressLevel2,
                            addressLevel3 = previous.addressLevel3,
                            postalCode = previous.postalCode,
                            country = previous.country,
                            tel = previous.tel,
                            email = previous.email,
                        ),
                    )
                }.onSuccess {
                    eventHandler.trySend { it.navigateBack() }
                }.onFailure {
                    viewModelStateFlow.update { it.copy(isSaving = false) }
                }
            }
        }
    }

    val uiState: StateFlow<AddressEditScreenUiState> = MutableStateFlow(
        AddressEditScreenUiState(
            callbacks = callbacks,
            isNew = addressId == AddressesScreenViewModel.NEW_ADDRESS_ID,
            isLoading = addressId != AddressesScreenViewModel.NEW_ADDRESS_ID,
            givenName = "",
            additionalName = "",
            familyName = "",
            organization = "",
            streetAddress = "",
            addressLevel1 = "",
            addressLevel2 = "",
            addressLevel3 = "",
            postalCode = "",
            country = "",
            tel = "",
            email = "",
            canSave = false,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            viewModelStateFlow.collect { state ->
                uiStateFlow.update {
                    AddressEditScreenUiState(
                        callbacks = callbacks,
                        isNew = state.id == AddressesScreenViewModel.NEW_ADDRESS_ID,
                        isLoading = state.isLoading,
                        givenName = state.givenName,
                        additionalName = state.additionalName,
                        familyName = state.familyName,
                        organization = state.organization,
                        streetAddress = state.streetAddress,
                        addressLevel1 = state.addressLevel1,
                        addressLevel2 = state.addressLevel2,
                        addressLevel3 = state.addressLevel3,
                        postalCode = state.postalCode,
                        country = state.country,
                        tel = state.tel,
                        email = state.email,
                        canSave = state.canSave,
                    )
                }
            }
        }
    }.asStateFlow()

    init {
        if (addressId != AddressesScreenViewModel.NEW_ADDRESS_ID) {
            viewModelScope.launch {
                val entity = addressRepository.getById(addressId)
                if (entity != null) {
                    viewModelStateFlow.value = ViewModelState.fromEntity(entity)
                } else {
                    viewModelStateFlow.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    interface Event {
        fun navigateBack()
    }

    data class ViewModelState(
        val id: Long = AddressesScreenViewModel.NEW_ADDRESS_ID,
        val givenName: String = "",
        val additionalName: String = "",
        val familyName: String = "",
        val organization: String = "",
        val streetAddress: String = "",
        val addressLevel1: String = "",
        val addressLevel2: String = "",
        val addressLevel3: String = "",
        val postalCode: String = "",
        val country: String = "",
        val tel: String = "",
        val email: String = "",
        val isSaving: Boolean = false,
        val isLoading: Boolean = false,
    ) {
        val canSave: Boolean
            get() = !isSaving && !isLoading && listOf(
                givenName,
                additionalName,
                familyName,
                organization,
                streetAddress,
                addressLevel1,
                addressLevel2,
                addressLevel3,
                postalCode,
                country,
                tel,
                email,
            ).any { it.isNotBlank() }

        companion object {
            fun fromEntity(entity: AddressEntity): ViewModelState {
                return ViewModelState(
                    id = entity.id,
                    givenName = entity.givenName,
                    additionalName = entity.additionalName,
                    familyName = entity.familyName,
                    organization = entity.organization,
                    streetAddress = entity.streetAddress,
                    addressLevel1 = entity.addressLevel1,
                    addressLevel2 = entity.addressLevel2,
                    addressLevel3 = entity.addressLevel3,
                    postalCode = entity.postalCode,
                    country = entity.country,
                    tel = entity.tel,
                    email = entity.email,
                )
            }
        }
    }
}
