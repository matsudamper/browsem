package net.matsudamper.browser.data.address

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "address")
data class AddressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
