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
        return combine(
            dao.observePathCounts(
                scheme = origin.scheme,
                host = origin.host,
                port = origin.port,
            ),
            dao.observePreferencesForOrigin(
                scheme = origin.scheme,
                host = origin.host,
                port = origin.port,
            ),
        ) { pathCounts, preferences ->
            val preferencesByPath = preferences.groupBy { it.path }
            pathCounts.map { row ->
                val pathPreferences = preferencesByPath[row.path].orEmpty()
                SavedFormPathInfo(
                    path = row.path,
                    fieldCount = row.fieldCount,
                    enabled = resolvePathEnabled(pathPreferences),
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
        ) { fieldKeys, preferences, values ->
            val pathEnabled = resolvePathEnabled(preferences)
            val fieldPreferences = preferences.associate { it.fieldKey to it.enabled }
            fieldKeys.map { fieldKey ->
                val previewValues = values
                    .asSequence()
                    .filter { it.fieldKey == fieldKey && it.value.isNotBlank() }
                    .groupBy { it.value }
                    .map { (_, entries) -> entries.maxBy { it.createdAt } }
                    .sortedByDescending { it.createdAt }
                    .take(FIELD_VALUE_PREVIEW_LIMIT)
                    .map { it.value }
                    .toList()
                SavedFormFieldInfo(
                    fieldKey = fieldKey,
                    values = previewValues,
                    enabled = pathEnabled && (fieldPreferences[fieldKey] ?: false),
                )
            }
        }.distinctUntilChanged()
    }

    fun observePathEnabled(origin: FormInputOrigin, path: String): Flow<Boolean> {
        return dao.observePreferencesForPath(
            scheme = origin.scheme,
            host = origin.host,
            port = origin.port,
            path = path,
        ).map { preferences ->
            resolvePathEnabled(preferences)
        }.distinctUntilChanged()
    }

    suspend fun getPathEnabled(origin: FormInputOrigin, path: String): Boolean {
        return isPathEnabled(origin, path)
    }

    suspend fun saveFields(pageKey: FormInputPageKey, fields: List<FormFieldEntry>) {
        val origin = pageKey.origin()
        if (!isPathEnabled(origin, pageKey.path)) return
        val now = System.currentTimeMillis()
        fields.forEach { field ->
            if (field.fieldKey.isBlank() || field.value.isBlank()) return@forEach
            if (!isFieldEnabled(origin, pageKey.path, field.fieldKey)) return@forEach
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
    }

    suspend fun getSuggestions(
        pageKey: FormInputPageKey,
        fieldKey: String,
        limit: Int = SUGGESTION_LIMIT,
    ): List<String> {
        if (fieldKey.isBlank()) return emptyList()
        val origin = pageKey.origin()
        if (!isPathEnabled(origin, pageKey.path)) return emptyList()
        if (!isFieldEnabled(origin, pageKey.path, fieldKey)) return emptyList()
        return dao.getDistinctValuesForField(
            scheme = pageKey.scheme,
            host = pageKey.host,
            port = pageKey.port,
            path = pageKey.path,
            fieldKey = fieldKey,
            limit = limit,
        )
    }

    suspend fun setPathEnabled(origin: FormInputOrigin, path: String, enabled: Boolean) {
        dao.upsertPreference(
            FormInputPreferenceEntity(
                scheme = origin.scheme,
                host = origin.host,
                port = origin.port,
                path = path,
                fieldKey = FORM_INPUT_PATH_SCOPE_FIELD_KEY,
                enabled = enabled,
            ),
        )
    }

    suspend fun setFieldEnabled(
        origin: FormInputOrigin,
        path: String,
        fieldKey: String,
        enabled: Boolean,
    ) {
        dao.upsertPreference(
            FormInputPreferenceEntity(
                scheme = origin.scheme,
                host = origin.host,
                port = origin.port,
                path = path,
                fieldKey = fieldKey,
                enabled = enabled,
            ),
        )
    }

    suspend fun getFieldEnabled(
        origin: FormInputOrigin,
        path: String,
        fieldKey: String,
    ): Boolean {
        return isFieldEnabled(origin, path, fieldKey)
    }

    /**
     * 長押しダイアログで選択された field を有効化し、値があれば保存する。
     */
    suspend fun enableFieldsAndSave(
        pageKey: FormInputPageKey,
        fields: List<FormFieldEntry>,
        enabledFieldKeys: Set<String>,
    ) {
        val origin = pageKey.origin()
        if (!isPathEnabled(origin, pageKey.path)) return
        val now = System.currentTimeMillis()
        fields.forEach { field ->
            if (field.fieldKey.isBlank()) return@forEach
            val enabled = field.fieldKey in enabledFieldKeys
            setFieldEnabled(origin, pageKey.path, field.fieldKey, enabled)
            if (!enabled || field.value.isBlank()) return@forEach
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
        if (dao.countFieldsForPath(
                scheme = origin.scheme,
                host = origin.host,
                port = origin.port,
                path = path,
            ) == 0
        ) {
            dao.deletePreferencesForPath(
                scheme = origin.scheme,
                host = origin.host,
                port = origin.port,
                path = path,
            )
        }
    }

    suspend fun deleteAll() {
        dao.deleteAllValues()
        dao.deleteAllPreferences()
    }

    private fun resolvePathEnabled(preferences: List<FormInputPreferenceEntity>): Boolean {
        return preferences.find { it.fieldKey == FORM_INPUT_PATH_SCOPE_FIELD_KEY }?.enabled ?: true
    }

    private suspend fun isPathEnabled(origin: FormInputOrigin, path: String): Boolean {
        return dao.getPreferenceEnabled(
            scheme = origin.scheme,
            host = origin.host,
            port = origin.port,
            path = path,
            fieldKey = FORM_INPUT_PATH_SCOPE_FIELD_KEY,
        ) ?: true
    }

    private suspend fun isFieldEnabled(origin: FormInputOrigin, path: String, fieldKey: String): Boolean {
        return dao.getPreferenceEnabled(
            scheme = origin.scheme,
            host = origin.host,
            port = origin.port,
            path = path,
            fieldKey = fieldKey,
        ) ?: false
    }

    companion object {
        const val SUGGESTION_LIMIT: Int = 5
        const val FIELD_VALUE_PREVIEW_LIMIT: Int = 3
    }
}

data class FormFieldEntry(
    val fieldKey: String,
    val value: String,
)

data class SavedFormPathInfo(
    val path: String,
    val fieldCount: Int,
    val enabled: Boolean,
)

data class SavedFormFieldInfo(
    val fieldKey: String,
    val values: List<String>,
    val enabled: Boolean,
)

fun displayFormInputPath(path: String): String {
    return if (path.isEmpty()) "/" else path
}
