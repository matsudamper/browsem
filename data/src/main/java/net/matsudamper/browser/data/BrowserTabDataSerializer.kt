package net.matsudamper.browser.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

internal object BrowserTabDataSerializer : Serializer<BrowserTabData> {
    override val defaultValue: BrowserTabData = BrowserTabData.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): BrowserTabData {
        try {
            return BrowserTabData.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(t: BrowserTabData, output: OutputStream) {
        t.writeTo(output)
    }
}
