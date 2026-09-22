package net.matsudamper.browser.data

import java.util.UUID

data class ProfileId(val value: String) {
    /**
     * GeckoSession に渡す contextId。
     * デフォルトプロファイルはプロファイル導入前の Cookie 等を引き継ぐため contextId を持たない。
     */
    val geckoContextId: String? get() = value.takeUnless { it == DEFAULT.value }

    companion object {
        val DEFAULT: ProfileId = ProfileId("default")

        fun generate(): ProfileId = ProfileId(UUID.randomUUID().toString())

        /** GeckoSession の contextId からプロファイルを逆引きする */
        fun fromGeckoContextId(contextId: String?): ProfileId {
            return if (contextId.isNullOrEmpty()) DEFAULT else ProfileId(contextId)
        }
    }
}
