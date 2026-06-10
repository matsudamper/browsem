package net.matsudamper.browser.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

internal object SiteSettingsSerializer : Serializer<SiteSettings> {
    override val defaultValue: SiteSettings = SiteSettings.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): SiteSettings {
        try {
            return SiteSettings.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(t: SiteSettings, output: OutputStream) {
        t.writeTo(output)
    }
}
