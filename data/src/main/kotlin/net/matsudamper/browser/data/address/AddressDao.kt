package net.matsudamper.browser.data.address

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AddressDao {
    @Insert
    suspend fun insert(entity: AddressEntity): Long

    @Update
    suspend fun update(entity: AddressEntity)

    @Query("SELECT * FROM address ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AddressEntity>>

    @Query("SELECT * FROM address ORDER BY updatedAt DESC")
    suspend fun getAll(): List<AddressEntity>

    @Query("SELECT * FROM address WHERE id = :id")
    suspend fun getById(id: Long): AddressEntity?

    @Query("DELETE FROM address WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM address")
    suspend fun deleteAll()
}
