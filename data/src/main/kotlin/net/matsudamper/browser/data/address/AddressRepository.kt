package net.matsudamper.browser.data.address

import android.content.Context
import kotlinx.coroutines.flow.Flow

class AddressRepository(context: Context) {
    private val db = AddressDatabase.getInstance(context)
    private val dao = db.addressDao()

    suspend fun save(entity: AddressEntity): Long {
        val existing = dao.getById(entity.id)
        return if (existing != null) {
            dao.update(entity.copy(updatedAt = System.currentTimeMillis()))
            entity.id
        } else {
            dao.insert(entity)
        }
    }

    suspend fun getAll(): List<AddressEntity> = dao.getAll()

    fun observeAll(): Flow<List<AddressEntity>> = dao.observeAll()

    suspend fun getById(id: Long): AddressEntity? = dao.getById(id)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()
}
