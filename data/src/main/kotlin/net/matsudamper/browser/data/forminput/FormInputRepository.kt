package net.matsudamper.browser.data.forminput

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

class FormInputRepository(context: Context) {
    private val dao = FormInputDatabase.getInstance(context).formInputDao()

    fun observeSavedPathCount(host: String): Flow<Int> {
        return dao.observePathCount(host).distinctUntilChanged()
    }

    fun observeSavedPaths(host: String): Flow<List<SavedFormPathInfo>> {
        return combine(
            dao.observePathCounts(host),
            dao.observePreferencesForHost(host),
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

    fun observeSavedFields(host: String, path: String): Flow<List<SavedFormFieldInfo>> {
        return combine(
            dao.observeDistinctFieldKeys(host, path),
            dao.observePreferencesForPath(host, path),
        ) { fieldKeys, preferences ->
            val pathEnabled = resolvePathEnabled(preferences)
            val fieldPreferences = preferences.associate { it.fieldKey to it.enabled }
            fieldKeys.map { fieldKey ->
                SavedFormFieldInfo(
                    fieldKey = fieldKey,
                    values = emptyList(),
                    enabled = pathEnabled && (fieldPreferences[fieldKey] ?: true),
                )
            }
        }.distinctUntilChanged().mapLatest { fields ->
            fields.map { field ->
                field.copy(
                    values = dao.getDistinctValuesForField(
                        host = host,
                        path = path,
                        fieldKey = field.fieldKey,
                        limit = FIELD_VALUE_PREVIEW_LIMIT,
                    ),
                )
            }
        }
    }

    fun observePathEnabled(host: String, path: String): Flow<Boolean> {
        return dao.observePreferencesForPath(host, path).map { preferences ->
            resolvePathEnabled(preferences)
        }.distinctUntilChanged()
    }

    suspend fun getPathEnabled(host: String, path: String): Boolean {
        return isPathEnabled(host, path)
    }

    suspend fun saveFields(pageKey: FormInputPageKey, fields: List<FormFieldEntry>) {
        if (!isPathEnabled(pageKey.host, pageKey.path)) return
        val now = System.currentTimeMillis()
        fields.forEach { field ->
            if (field.fieldKey.isBlank() || field.value.isBlank()) return@forEach
            if (!isFieldEnabled(pageKey.host, pageKey.path, field.fieldKey)) return@forEach
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
        if (!isPathEnabled(pageKey.host, pageKey.path)) return emptyList()
        if (!isFieldEnabled(pageKey.host, pageKey.path, fieldKey)) return emptyList()
        return dao.getDistinctValuesForField(
            host = pageKey.host,
            path = pageKey.path,
            fieldKey = fieldKey,
            limit = limit,
        )
    }

    suspend fun setPathEnabled(host: String, path: String, enabled: Boolean) {
        dao.upsertPreference(
            FormInputPreferenceEntity(
                host = host,
                path = path,
                fieldKey = FORM_INPUT_PATH_SCOPE_FIELD_KEY,
                enabled = enabled,
            ),
        )
    }

    suspend fun setFieldEnabled(host: String, path: String, fieldKey: String, enabled: Boolean) {
        dao.upsertPreference(
            FormInputPreferenceEntity(
                host = host,
                path = path,
                fieldKey = fieldKey,
                enabled = enabled,
            ),
        )
    }

    suspend fun deletePath(host: String, path: String) {
        dao.deleteValuesForPath(host, path)
        dao.deletePreferencesForPath(host, path)
    }

    suspend fun deleteField(host: String, path: String, fieldKey: String) {
        dao.deleteValuesForField(host, path, fieldKey)
        dao.deletePreferenceForField(host, path, fieldKey)
    }

    suspend fun deleteAll() {
        dao.deleteAllValues()
        dao.deleteAllPreferences()
    }

    private fun resolvePathEnabled(preferences: List<FormInputPreferenceEntity>): Boolean {
        return preferences.find { it.fieldKey == FORM_INPUT_PATH_SCOPE_FIELD_KEY }?.enabled ?: true
    }

    private suspend fun isPathEnabled(host: String, path: String): Boolean {
        return dao.getPreferenceEnabled(host, path, FORM_INPUT_PATH_SCOPE_FIELD_KEY) ?: true
    }

    private suspend fun isFieldEnabled(host: String, path: String, fieldKey: String): Boolean {
        return dao.getPreferenceEnabled(host, path, fieldKey) ?: true
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
