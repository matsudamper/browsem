package net.matsudamper.browser.data

/** プロファイルに設定できるアイコン。DB には name を保存する */
enum class ProfileIcon {
    PERSON,
    WORK,
    SCHOOL,
    HOME,
    SHOPPING_CART,
    STAR,
    FAVORITE,
    ;

    companion object {
        fun fromKeyOrDefault(key: String): ProfileIcon {
            return entries.firstOrNull { it.name == key } ?: PERSON
        }
    }
}
