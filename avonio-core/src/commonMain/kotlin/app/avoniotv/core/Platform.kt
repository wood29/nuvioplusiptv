/*
 * avonioTV core: written from scratch for avonioTV (see core/LICENSE).
 */
package app.avoniotv.core

/** Keeps the guide between starts. [read] gives the guide and when it was loaded. */
/** Keeps the last channel list on the device, so the next start shows it at once (then refreshes). */
interface LineupStore {
    fun read(source: Source, extras: List<Source>): Lineup?
    fun write(lineup: Lineup)
}

interface GuideStore {
    fun read(url: String, now: Long): Pair<Guide, Long>?
    fun write(url: String, guide: Guide, now: Long)
}

/** "Amélie" → "amelie": letters without their accents (to match titles across languages). */
internal expect fun withoutAccents(s: String): String

/** Reads a JSON array entry by entry as it arrives (provider lists can hold 100,000 titles). */
internal expect suspend fun readJsonArray(channel: io.ktor.utils.io.ByteReadChannel, json: kotlinx.serialization.json.Json, onEntry: (kotlinx.serialization.json.JsonElement) -> Unit)
