package net.matsudamper.browser.data.forminput

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class FormInputRepository(context: Context) {
    private val dao = FormInputDatabase.getInstance(context).formInputDao()

    fun observeSavedPathCount(origin: FormInputOrigin): Flow<Int> {
        return dao.observePathCount(
            scheme = origin.scheme,
            host = origin.host,
            port = origin.port,
        ).distinctUntilChanged()
    }

    fun observeSavedPaths(origin: FormInputOrigin): Flow<List<SavedFormPathInfo>> {
        return dao.observePathCounts(
            scheme = origin.scheme,
            host = origin.host,
            port = origin.port,
        ).map { pathCounts ->
            pathCounts.map { row ->
                SavedFormPathInfo(
                    path = row.path,
                    fieldCount = row.fieldCount,
                )
            }
        }.distinctUntilChanged()
    }

    fun observeSavedFields(origin: FormInputOrigin, path: String): Flow<List<SavedFormFieldInfo>> {
        return combine(
            dao.observeDistinctFieldKeys(
                scheme = origin.scheme,
                host = origin.host,
                port = origin.port,
                path = path,
            ),
            dao.observePreferencesForPath(
                scheme = origin.scheme,
                host = origin.host,
                port = origin.port,
                path = path,
            ),
            dao.observeValuesForPath(
                scheme = origin.scheme,
                host = origin.host,
                port = origin.port,
                path = path,
            ),
        ) { valueFieldKeys, preferences, values ->
            val registeredFieldKeys = preferences
                .asSequence()
                .filter { it.fieldKey != FORM_INPUT_PATH_SCOPE_FIELD_KEY }
                .map { it.fieldKey }
                .toSet()
            val valuePreviews = values
                .asSequence()
                .filter { it.value.isNotBlank() }
                .groupBy { it.fieldKey }
            (registeredFieldKeys + valueFieldKeys)
                .sorted()
                .map { fieldKey ->
                    val previewValues = valuePreviews[fieldKey]
                        .orEmpty()
                        .groupBy { it.value }
                        .map { (_, entries) -> entries.maxBy { it.createdAt } }
                        .sortedByDescending { it.createdAt }
                        .map { it.value }
                    SavedFormFieldInfo(
                        fieldKey = fieldKey,
                        previewValues = previewValues,
                    )
                }
        }.distinctUntilChanged()
    }

    fun observeSavedValues(
        origin: FormInputOrigin,
        path: String,
        fieldKey: String,
    ): Flow<List<String>> {
        return dao.observeDistinctValuesForField(
            scheme = origin.scheme,
            host = origin.host,
            port = origin.port,
            path = path,
            fieldKey = fieldKey,
        ).distinctUntilChanged()
    }

    suspend fun saveFields(pageKey: FormInputPageKey, fields: List<FormFieldEntry>) {
        val origin = pageKey.origin()
        val now = System.currentTimeMillis()
        fields.forEach { field ->
            if (field.fieldKey.isBlank() || field.value.isBlank()) return@forEach
            if (!isFieldRegistered(origin, pageKey.path, field.fieldKey)) return@forEach
            val touched = dao.touchValue(
                scheme = pageKey.scheme,
                host = pageKey.host,
                port = pageKey.port,
                path = pageKey.path,
                fieldKey = field.fieldKey,
                value = field.value,
                createdAt = now,
            )
            if (touched == 0) {
                dao.insert(
                    FormFieldValueEntity(
                        scheme = pageKey.scheme,
                        host = pageKey.host,
                        port = pageKey.port,
                        path = pageKey.path,
                        fieldKey = field.fieldKey,
                        value = field.value,
                        createdAt = now,
                    ),
                )
            }
            val overflow = dao.countValueRowsForField(
                scheme = pageKey.scheme,
                host = pageKey.host,
                port = pageKey.port,
                path = pageKey.path,
                fieldKey = field.fieldKey,
            ) - MAX_FIELD_VALUE_ROWS
            if (overflow > 0) {
                dao.deleteOldestValuesForField(
                    scheme = pageKey.scheme,
                    host = pageKey.host,
                    port = pageKey.port,
                    path = pageKey.path,
                    fieldKey = field.fieldKey,
                    limit = overflow,
                )
            }
        }
    }

    suspend fun getSuggestions(
        pageKey: FormInputPageKey,
        fieldKey: String,
        limit: Int = SUGGESTION_LIMIT,
    ): List<String> {
        if (fieldKey.isBlank()) return emptyList()
        val origin = pageKey.origin()
        if (!isFieldRegistered(origin, pageKey.path, fieldKey)) return emptyList()
        return dao.getDistinctValuesForField(
            scheme = pageKey.scheme,
            host = pageKey.host,
            port = pageKey.port,
            path = pageKey.path,
            fieldKey = fieldKey,
            limit = limit,
        )
    }

    suspend fun registerField(origin: FormInputOrigin, path: String, fieldKey: String) {
        if (fieldKey.isBlank()) return
        dao.upsertPreference(
            FormInputPreferenceEntity(
                scheme = origin.scheme,
                host = origin.host,
                port = origin.port,
                path = path,
                fieldKey = fieldKey,
                enabled = true,
            ),
        )
    }

    suspend fun isFieldRegistered(
        origin: FormInputOrigin,
        path: String,
        fieldKey: String,
    ): Boolean {
        return isFieldRegisteredInternal(origin, path, fieldKey)
    }

    /**
     * 選択メニューから追加された field を登録し、値があれば保存する。
     */
    suspend fun registerFieldAndSave(
        pageKey: FormInputPageKey,
        fields: List<FormFieldEntry>,
    ) {
        val origin = pageKey.origin()
        fields.forEach { field ->
            if (field.fieldKey.isBlank()) return@forEach
            registerField(origin, pageKey.path, field.fieldKey)
        }
        saveFields(
            pageKey = pageKey,
            fields = fields.filter { field -> field.value.isNotBlank() },
        )
    }

    suspend fun deletePath(origin: FormInputOrigin, path: String) {
        dao.deleteValuesForPath(
            scheme = origin.scheme,
            host = origin.host,
            port = origin.port,
            path = path,
        )
        dao.deletePreferencesForPath(
            scheme = origin.scheme,
            host = origin.host,
            port = origin.port,
            path = path,
        )
    }

    suspend fun deleteField(origin: FormInputOrigin, path: String, fieldKey: String) {
        dao.deleteValuesForField(
            scheme = origin.scheme,
            host = origin.host,
            port = origin.port,
            path = path,
            fieldKey = fieldKey,
        )
        dao.deletePreferenceForField(
            scheme = origin.scheme,
            host = origin.host,
            port = origin.port,
            path = path,
            fieldKey = fieldKey,
        )
        cleanupPathPreferencesIfEmpty(origin, path)
    }

    suspend fun deleteValue(
        origin: FormInputOrigin,
        path: String,
        fieldKey: String,
        value: String,
    ) {
        if (value.isBlank()) return
        dao.deleteValueForField(
            scheme = origin.scheme,
            host = origin.host,
            port = origin.port,
            path = path,
            fieldKey = fieldKey,
            value = value,
        )
    }

    suspend fun deleteAll() {
        dao.deleteAllValues()
        dao.deleteAllPreferences()
    }

    private suspend fun cleanupPathPreferencesIfEmpty(origin: FormInputOrigin, path: String) {
        if (dao.countFieldsForPath(
                scheme = origin.scheme,
                host = origin.host,
                port = origin.port,
                path = path,
            ) != 0
        ) {
            return
        }
        if (dao.countFieldPreferencesForPath(
                scheme = origin.scheme,
                host = origin.host,
                port = origin.port,
                path = path,
            ) > 0
        ) {
            return
        }
        dao.deletePreferencesForPath(
            scheme = origin.scheme,
            host = origin.host,
            port = origin.port,
            path = path,
        )
    }

    private suspend fun isFieldRegisteredInternal(
        origin: FormInputOrigin,
        path: String,
        fieldKey: String,
    ): Boolean {
        return dao.getPreferenceEnabled(
            scheme = origin.scheme,
            host = origin.host,
            port = origin.port,
            path = path,
            fieldKey = fieldKey,
        ) != null
    }

    companion object {
        const val SUGGESTION_LIMIT: Int = 5
        const val MAX_FIELD_VALUE_ROWS: Int = 50
    }
}

data class FormFieldEntry(
    val fieldKey: String,
    val value: String,
)

data class SavedFormPathInfo(
    val path: String,
    val fieldCount: Int,
)

data class SavedFormFieldInfo(
    val fieldKey: String,
    val previewValues: List<String>,
)

fun displayFormInputPath(path: String): String {
    return if (path.isEmpty()) "/" else path
}
