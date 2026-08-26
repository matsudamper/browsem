package net.matsudamper.browser

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.address.AddressEntity
import net.matsudamper.browser.data.address.AddressRepository
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import java.util.Locale

class AutocompleteStorageDelegate(
    private val addressRepository: AddressRepository,
    private val coroutineScope: CoroutineScope,
    private val onAddressFetched: (Int) -> Unit = {},
) : Autocomplete.StorageDelegate {

    override fun onAddressFetch(): GeckoResult<Array<Autocomplete.Address>> {
        val result = GeckoResult<Array<Autocomplete.Address>>()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val addresses = addressRepository.getAll().map { it.toGeckoAddress() }.toTypedArray()
                Log.i(
                    TAG,
                    "onAddressFetch: ${addresses.size}件 " +
                        addresses.joinToString { address ->
                            "name=${address.name} family=${address.familyName} " +
                                "given=${address.givenName} country=${address.country} " +
                                "street=${address.streetAddress} postal=${address.postalCode}"
                        },
                )
                result.complete(addresses)
                onAddressFetched(addresses.size)
            } catch (e: Exception) {
                Log.w(TAG, "住所の取得に失敗", e)
                result.complete(emptyArray())
            }
        }
        return result
    }

    override fun onAddressSave(address: Autocomplete.Address) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val entity = address.toEntity()
                addressRepository.save(entity)
                Log.d(TAG, "住所を保存しました")
            } catch (e: Exception) {
                Log.w(TAG, "住所の保存に失敗", e)
            }
        }
    }

    override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>> {
        return GeckoResult.fromValue(emptyArray())
    }

    override fun onLoginSave(login: Autocomplete.LoginEntry) {
        // ログイン保存は未実装
    }

    override fun onLoginUsed(login: Autocomplete.LoginEntry, usedFields: Int) {
        // ログイン使用通知は未実装
    }

    override fun onCreditCardFetch(): GeckoResult<Array<Autocomplete.CreditCard>> {
        return GeckoResult.fromValue(emptyArray())
    }

    override fun onCreditCardSave(creditCard: Autocomplete.CreditCard) {
        // クレジットカード保存は未実装
    }

    companion object {
        private const val TAG = "AutocompleteStorage"
    }
}

internal fun AddressEntity.toGeckoAddress(): Autocomplete.Address {
    // GeckoViewAutocomplete.Address.isValid() は name ?? givenName で判定する。
    // name が空文字だと givenName があっても候補から除外され、選択ダイアログが出ない。
    return Autocomplete.Address.Builder()
        .guid(id.toString())
        .name(toGeckoFullName())
        .givenName(givenName)
        .additionalName(additionalName)
        .familyName(familyName)
        .organization(organization)
        .streetAddress(streetAddress)
        .addressLevel1(addressLevel1)
        .addressLevel2(addressLevel2)
        .addressLevel3(addressLevel3)
        .postalCode(postalCode)
        .country(toGeckoCountry())
        .tel(tel)
        .email(email)
        .build()
}

internal fun AddressEntity.toGeckoFullName(): String {
    return listOf(givenName, additionalName, familyName)
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}

/**
 * Gecko は addresses.supported=detect のとき、country が空のレコードを候補から除外する。
 * 設定画面では国を空のまま保存できるため、未入力なら端末ロケールの国を使う。
 */
internal fun AddressEntity.toGeckoCountry(): String {
    if (country.isNotBlank()) return country
    return Locale.getDefault().country.ifBlank { "JP" }
}

internal fun Autocomplete.Address.toEntity(): AddressEntity {
    val existingId = guid?.toLongOrNull() ?: 0L
    return AddressEntity(
        id = existingId,
        givenName = givenName,
        additionalName = additionalName,
        familyName = familyName,
        organization = organization,
        streetAddress = streetAddress,
        addressLevel1 = addressLevel1,
        addressLevel2 = addressLevel2,
        addressLevel3 = addressLevel3,
        postalCode = postalCode,
        country = country,
        tel = tel,
        email = email,
    )
}
