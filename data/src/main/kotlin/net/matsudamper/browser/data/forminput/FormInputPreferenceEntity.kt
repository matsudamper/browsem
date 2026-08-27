package net.matsudamper.browser.data.forminput

import androidx.room.Entity

/**
 * フォーム入力の保存・サジェストを path または field 単位で ON/OFF する設定。
 * [fieldKey] が空文字のときは path 全体の設定を表す。
 */
@Entity(
    tableName = "form_input_preference",
    primaryKeys = ["host", "path", "fieldKey"],
)
data class FormInputPreferenceEntity(
    val host: String,
    val path: String,
    val fieldKey: String,
    val enabled: Boolean,
)

/** path 全体の設定を表す fieldKey */
const val FORM_INPUT_PATH_SCOPE_FIELD_KEY: String = ""
