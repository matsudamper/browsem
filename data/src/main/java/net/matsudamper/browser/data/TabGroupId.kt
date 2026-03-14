package net.matsudamper.browser.data

import java.util.UUID

data class TabGroupId(val value: String) {
    companion object {
        fun generate(): TabGroupId = TabGroupId(UUID.randomUUID().toString())
    }
}
