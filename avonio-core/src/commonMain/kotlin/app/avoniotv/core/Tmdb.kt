/*
 * avonioTV core: written from scratch for avonioTV (see core/LICENSE).
 * Uses The Movie Database (TMDB) API with a key the user enters. avonioTV is not endorsed or
 * certified by TMDB.
 */
package app.avoniotv.core

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What TMDB adds to a details page. */
data class TmdbInfo(
    val id: Int,
    val kind: VodKind,
    val title: String,
    val year: Int?,
    val overview: String?,
    val genres: List<String>,
    val runtimeMin: Int?,
    val rating: Double?,
    val backdrop: String?,
    val poster: String?,
    val cast: List<TmdbPerson>,
    val trailerYouTubeId: String?,
    /** Recommended and similar titles, best first. */
    val related: List<TmdbTitle>,
    /** Age rating (Austria, else Germany, else the US), as a minimum age. */
    val age: Int? = null
)

data class TmdbPerson(val name: String, val character: String?, val photo: String?, val id: Int? = null)

/** An actor's (or director's) page: who they are, and everything they were in. */
data class TmdbPersonInfo(
    val id: Int,
    val name: String,
    val photo: String?,
    /** "Acting", "Directing"… */
    val department: String?,
    val birthday: String?,
    val deathday: String?,
    val birthplace: String?,
    val biography: String?,
    val credits: List<TmdbCredit>
)

/** One of the week's most watched titles on TMDB. */
data class TmdbTrend(val id: Int, val title: String, val original: String?, val year: Int?, val rating: Double?, val votes: Int)

/** One title in a filmography: the part played (or job), its rating, and how well known it is. */
data class TmdbCredit(val title: TmdbTitle, val role: String?, val rating: Double?, val votes: Int, val popularity: Double, val crew: Boolean)
data class TmdbTitle(val id: Int, val kind: VodKind, val title: String, val originalTitle: String?, val year: Int?, val poster: String?)

/**
 * The Movie Database: finds a title (by its TMDB id, else by name and year) and reads its
 * description, cast, trailer and related titles. [key] is a v3 API key or a v4 read token.
 */
class Tmdb(private val http: HttpClient, private val key: String, private val language: String = "en-US") {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = HashMap<String, TmdbInfo?>()

    private suspend fun get(path: String, vararg params: Pair<String, String>): JsonObject? {
        val res = http.get("https://api.themoviedb.org/3$path") {
            if (key.length > 40) header("Authorization", "Bearer $key") else parameter("api_key", key)
            parameter("language", language)
            params.forEach { (k, v) -> parameter(k, v) }
        }
        if (res.status.value == 401) throw IllegalStateException("TMDB didn't accept the key")
        if (!res.status.isSuccess()) return null
        return json.parseToJsonElement(res.bodyAsText()) as? JsonObject
    }

    /** True when TMDB accepts the key. */
    suspend fun check(): Boolean = runCatching { get("/configuration") != null }.getOrDefault(false)

    /** The TMDB id for a title: searched by its cleaned-up name, with the year when known. */
    suspend fun find(kind: VodKind, title: String, year: Int?): Int? {
        val path = if (kind == VodKind.MOVIE) "/search/movie" else "/search/tv"
        val yearKey = if (kind == VodKind.MOVIE) "year" else "first_air_date_year"
        suspend fun search(withYear: Boolean) = get(path, *buildList {
            add("query" to title); add("include_adult" to "false")
            if (withYear && year != null) add(yearKey to year.toString())
        }.toTypedArray())?.arr("results")?.firstOrNull()?.int("id")
        return search(true) ?: if (year != null) search(false) else null
    }

    /** What's most watched this week (two pages, about 40 titles), with TMDB's rating. */
    suspend fun trending(kind: VodKind): List<TmdbTrend> = (1..2).flatMap { page ->
        get(if (kind == VodKind.MOVIE) "/trending/movie/week" else "/trending/tv/week", "page" to page.toString())?.arr("results").orEmpty().mapNotNull { r ->
            val id = r.int("id") ?: return@mapNotNull null
            TmdbTrend(
                id, r.str("title") ?: r.str("name") ?: return@mapNotNull null, r.str("original_title") ?: r.str("original_name"),
                (r.str("release_date") ?: r.str("first_air_date"))?.take(4)?.toIntOrNull(),
                (r["vote_average"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it > 0 }, r.int("vote_count") ?: 0
            )
        }
    }

    /** Everything for the details page; null when TMDB doesn't know the title. */
    suspend fun info(kind: VodKind, tmdbId: Int?, title: String, year: Int?): TmdbInfo? {
        val cacheKey = "${kind.name}:${tmdbId ?: "$title|$year"}"
        if (cache.containsKey(cacheKey)) return cache[cacheKey]
        val id = tmdbId ?: find(kind, title, year)
        val info = id?.let { load(kind, it) }
        cache[cacheKey] = info
        return info
    }

    private suspend fun load(kind: VodKind, id: Int): TmdbInfo? {
        val path = if (kind == VodKind.MOVIE) "/movie/$id" else "/tv/$id"
        val o = get(path, "append_to_response" to if (kind == VodKind.MOVIE) "credits,videos,recommendations,similar,release_dates" else "credits,videos,recommendations,similar,content_ratings", "include_video_language" to "en,null,de") ?: return null
        val date = o.str(if (kind == VodKind.MOVIE) "release_date" else "first_air_date")
        val cast = o.obj("credits")?.arr("cast").orEmpty().take(12).mapNotNull { p ->
            val name = p.str("name") ?: return@mapNotNull null
            TmdbPerson(name, p.str("character")?.ifBlank { null }, p.str("profile_path")?.let { IMG_FACE + it }, p.int("id"))
        }
        val trailer = o.obj("videos")?.arr("results").orEmpty()
            .filter { it.str("site") == "YouTube" && it.str("type") in setOf("Trailer", "Teaser") }
            .sortedBy { if (it.str("type") == "Trailer") 0 else 1 }
            .firstOrNull()?.str("key")
        val related = (o.obj("recommendations")?.arr("results").orEmpty() + o.obj("similar")?.arr("results").orEmpty())
            .mapNotNull { r ->
                val rid = r.int("id") ?: return@mapNotNull null
                val t = r.str("title") ?: r.str("name") ?: return@mapNotNull null
                TmdbTitle(
                    rid, kind, t, r.str("original_title") ?: r.str("original_name"),
                    (r.str("release_date") ?: r.str("first_air_date"))?.take(4)?.toIntOrNull(),
                    r.str("poster_path")?.let { IMG_POSTER + it }
                )
            }.distinctBy { it.id }
        val runtime = if (kind == VodKind.MOVIE) o.int("runtime")
            else (o["episode_run_time"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
        return TmdbInfo(
            id = id, kind = kind,
            title = o.str("title") ?: o.str("name").orEmpty(),
            year = date?.take(4)?.toIntOrNull(),
            overview = o.str("overview")?.ifBlank { null },
            genres = o.arr("genres").orEmpty().mapNotNull { it.str("name") },
            runtimeMin = runtime?.takeIf { it > 0 },
            rating = (o["vote_average"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it > 0 },
            backdrop = o.str("backdrop_path")?.let { IMG_BACKDROP + it },
            poster = o.str("poster_path")?.let { IMG_POSTER + it },
            cast = cast, trailerYouTubeId = trailer, related = related,
            age = certification(o, kind)
        )
    }

    private val people = HashMap<Int, TmdbPersonInfo?>()

    /** An actor's page; the biography falls back to English when there is none in your language. */
    suspend fun person(id: Int): TmdbPersonInfo? {
        if (people.containsKey(id)) return people[id]
        val o = get("/person/$id", "append_to_response" to "combined_credits") ?: return null
        var bio = o.str("biography")?.ifBlank { null }
        if (bio == null && !language.startsWith("en")) bio = runCatching { Tmdb(http, key, "en-US").get("/person/$id")?.str("biography")?.ifBlank { null } }.getOrNull()
        val cc = o.obj("combined_credits")
        fun credit(r: JsonObject, crew: Boolean): TmdbCredit? {
            val rid = r.int("id") ?: return null
            val kind = when (r.str("media_type")) { "movie" -> VodKind.MOVIE; "tv" -> VodKind.SERIES; else -> return null }
            val t = r.str("title") ?: r.str("name") ?: return null
            // Talk shows, award nights and the like: a guest appearance, not part of a filmography.
            if (kind == VodKind.SERIES && !crew &&
                (r["genre_ids"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull in setOf("10767", "10763", "10764") } == true) return null
            if (r.str("character")?.contains("Self", ignoreCase = true) == true) return null
            return TmdbCredit(
                TmdbTitle(rid, kind, t, r.str("original_title") ?: r.str("original_name"),
                    (r.str("release_date") ?: r.str("first_air_date"))?.take(4)?.toIntOrNull(), r.str("poster_path")?.let { IMG_POSTER + it }),
                role = (if (crew) r.str("job") else r.str("character"))?.ifBlank { null },
                rating = (r["vote_average"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it > 0 },
                votes = r.int("vote_count") ?: 0,
                popularity = (r["popularity"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                crew = crew
            )
        }
        val acting = cc?.arr("cast").orEmpty().mapNotNull { credit(it, false) }
        // Directors and writers: their own work too (an actor's crew jobs only when they're mainly crew).
        val dept = o.str("known_for_department")
        val crew = if (dept != null && dept != "Acting") cc?.arr("crew").orEmpty().filter { it.str("job") in setOf("Director", "Screenplay", "Writer", "Creator") }.mapNotNull { credit(it, true) } else emptyList()
        val credits = (acting + crew).groupBy { it.title.kind to it.title.id }.values.map { same -> same.first().copy(role = same.mapNotNull { it.role }.distinct().take(2).joinToString(" · ").ifBlank { null }) }
        val info = TmdbPersonInfo(
            id = id, name = o.str("name").orEmpty(), photo = o.str("profile_path")?.let { IMG_PROFILE + it },
            department = dept, birthday = o.str("birthday")?.ifBlank { null }, deathday = o.str("deathday")?.ifBlank { null },
            birthplace = o.str("place_of_birth")?.ifBlank { null }, biography = bio, credits = credits
        )
        people[id] = info
        return info
    }

    /** The age rating: Austria first, then Germany, then the US. */
    private fun certification(o: JsonObject, kind: VodKind): Int? {
        val byCountry: Map<String, List<String>> = if (kind == VodKind.MOVIE)
            o.obj("release_dates")?.arr("results").orEmpty().associate { r ->
                r.str("iso_3166_1").orEmpty() to r.arr("release_dates").orEmpty().mapNotNull { it.str("certification")?.ifBlank { null } }
            }
        else o.obj("content_ratings")?.arr("results").orEmpty().associate { r -> r.str("iso_3166_1").orEmpty() to listOfNotNull(r.str("rating")?.ifBlank { null }) }
        return listOf("AT", "DE", "US").firstNotNullOfOrNull { c -> byCountry[c]?.firstNotNullOfOrNull { AgeRating.parse(it) } }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = str(k)?.toDoubleOrNull()?.toInt()
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }

    companion object {
        const val IMG_POSTER = "https://image.tmdb.org/t/p/w342"
        const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"
        const val IMG_FACE = "https://image.tmdb.org/t/p/w185"
        const val IMG_PROFILE = "https://image.tmdb.org/t/p/h632"

        /**
         * The provider's titles that match TMDB's related titles (same name, year within one),
         * in TMDB's order. [index] maps OnDemand.normalize(title) to the provider's titles.
         */
        fun match(related: List<TmdbTitle>, index: Map<String, List<VodItem>>, exclude: String? = null): List<VodItem> =
            related.mapNotNull { r ->
                val names = listOfNotNull(r.title, r.originalTitle).map { OnDemand.normalize(it) }.distinct()
                names.firstNotNullOfOrNull { n ->
                    index[n]?.firstOrNull { it.key != exclude && (r.year == null || it.year == null || kotlin.math.abs(it.year - r.year) <= 1) }
                }
            }.distinctBy { it.key }
    }
}
