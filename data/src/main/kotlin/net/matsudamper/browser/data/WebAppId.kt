package net.matsudamper.browser.data

import java.util.UUID

data class WebAppId(val value: String) {
    companion object {
        fun generate(): WebAppId = WebAppId(UUID.randomUUID().toString())
    }
}
