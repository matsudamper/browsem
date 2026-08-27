package net.matsudamper.browser.data.forminput

import android.content.Context

class FormInputRepository(context: Context) {
    private val dao = FormInputDatabase.getInstance(context).formInputDao()

    suspend fun saveFields(pageKey: FormInputPageKey, fields: List<FormFieldEntry>) {
        val now = System.currentTimeMillis()
        fields.forEach { field ->
            if (field.fieldKey.isBlank() || field.value.isBlank()) return@forEach
            dao.insert(
                FormFieldValueEntity(
                    host = pageKey.host,
                    path = pageKey.path,
                    fieldKey = field.fieldKey,
                    value = field.value,
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun getSuggestions(
        pageKey: FormInputPageKey,
        fieldKey: String,
        limit: Int = SUGGESTION_LIMIT,
    ): List<String> {
        if (fieldKey.isBlank()) return emptyList()
        return dao.getDistinctValuesForField(
            host = pageKey.host,
            path = pageKey.path,
            fieldKey = fieldKey,
            limit = limit,
        )
    }

    suspend fun deleteAll() = dao.deleteAll()

    companion object {
        const val SUGGESTION_LIMIT: Int = 5
    }
}

data class FormFieldEntry(
    val fieldKey: String,
    val value: String,
)
