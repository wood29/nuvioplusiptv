/*
 * avonioTV core: written from scratch for avonioTV (see core/LICENSE).
 */
package app.avoniotv.core

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeToSequence

/**
 * The TV box: read on an I/O thread, so the download (which runs on other threads) is never
 * stuck behind it. Reading on a busy single thread froze.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal actual suspend fun readJsonArray(channel: ByteReadChannel, json: Json, onEntry: (JsonElement) -> Unit) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        for (el in json.decodeToSequence(channel.toInputStream(), JsonElement.serializer(), kotlinx.serialization.json.DecodeSequenceMode.ARRAY_WRAPPED)) onEntry(el)
    }
}
