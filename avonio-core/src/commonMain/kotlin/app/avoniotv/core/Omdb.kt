/*
 * avonioTV core: written from scratch for avonioTV (see core/LICENSE).
 */
package app.avoniotv.core

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** IMDb's rating (and Rotten Tomatoes, Metacritic when known) for one title. */
data class OmdbRatings(val imdb: Double?, val imdbVotes: Int?, val rotten: Int?, val metacritic: Int?)

/**
 * OMDb (omdbapi.com): the IMDb rating of a film or series, found by IMDb id, else by title and
 * year. A free key allows about 1,000 lookups a day, so the apps keep what it returns.
 */
class Omdb(private val http: HttpClient, private val key: String) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun get(vararg params: Pair<String, String>): JsonObject? {
        val res = http.get("https://www.omdbapi.com/") { parameter("apikey", key); params.forEach { (k, v) -> parameter(k, v) } }
        if (res.status.value == 401) throw IllegalStateException("OMDb didn't accept the key")
        if (!res.status.isSuccess()) return null
        val o = json.parseToJsonElement(res.bodyAsText()) as? JsonObject ?: return null
        if (o.str("Response") == "False") { if (o.str("Error")?.contains("API key", true) == true) throw IllegalStateException("OMDb didn't accept the key"); return null }
        return o
    }

    /** True when OMDb accepts the key. */
    suspend fun check(): Boolean = runCatching { get("i" to "tt0111161") != null }.getOrDefault(false)

    suspend fun ratings(kind: VodKind, title: String, year: Int?, imdbId: String? = null): OmdbRatings? {
        val o = (if (!imdbId.isNullOrBlank()) get("i" to imdbId) else null)
            ?: get(*buildList { add("t" to title); add("type" to if (kind == VodKind.MOVIE) "movie" else "series"); year?.let { add("y" to it.toString()) } }.toTypedArray())
            ?: (if (year != null) get("t" to title, "type" to if (kind == VodKind.MOVIE) "movie" else "series") else null)
            ?: return null
        val list = (o["Ratings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun of(src: String) = list.firstOrNull { it.str("Source") == src }?.str("Value")
        return OmdbRatings(
            o.str("imdbRating")?.toDoubleOrNull(),
            o.str("imdbVotes")?.replace(",", "")?.toIntOrNull(),
            of("Rotten Tomatoes")?.removeSuffix("%")?.toIntOrNull(),
            of("Metacritic")?.substringBefore('/')?.toIntOrNull()
        )
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
