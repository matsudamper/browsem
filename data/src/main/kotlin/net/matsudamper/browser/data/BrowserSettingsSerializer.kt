package net.matsudamper.browser.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

internal object BrowserSettingsSerializer : Serializer<BrowserSettings> {
    override val defaultValue: BrowserSettings = BrowserSettings.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): BrowserSettings {
        try {
            return BrowserSettings.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(t: BrowserSettings, output: OutputStream) {
        t.writeTo(output)
    }
}
