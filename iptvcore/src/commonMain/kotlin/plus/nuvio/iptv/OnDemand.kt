/*
 * Nuvio Plus On Demand engine: written from scratch (see iptvcore/LICENSE); the same code as
 * avonioTV's core. Not derived from Nuvio or any GPL code.
 */
package plus.nuvio.iptv

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class VodKind { MOVIE, SERIES }

data class VodCategory(val id: String, val name: String, val kind: VodKind)

/** One movie or series in the provider's lists. */
data class VodItem(
    /** The provider's id: stream_id for movies, series_id for series. */
    val id: String,
    val kind: VodKind,
    val name: String,
    val categoryId: String,
    val poster: String? = null,
    val rating: Double? = null,
    val year: Int? = null,
    /** When the provider added it (epoch seconds), for "Recently added". */
    val added: Long = 0,
    /** File type for movies ("mkv", "mp4"…). */
    val container: String? = null,
    val tmdbId: String? = null,
    val plot: String? = null,
    val genre: String? = null
) {
    /** Unique across movies and series. */
    val key: String get() = (if (kind == VodKind.MOVIE) "m:" else "s:") + id
    /** The name without provider tags such as "DE - " or "[4K]", for display and search. */
    val title: String by lazy(LazyThreadSafetyMode.PUBLICATION) { OnDemand.cleanTitle(name) }
}

data class VodEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val container: String,
    val plot: String? = null,
    val image: String? = null,
    val durationSecs: Int? = null
)

/** The details page: the provider's full info for one title. */
data class VodDetails(
    val item: VodItem,
    val plot: String? = null,
    val backdrop: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val durationSecs: Int? = null,
    val releaseDate: String? = null,
    val trailerYouTubeId: String? = null,
    val tmdbId: String? = null,
    /** Series only: episodes by season. */
    val episodes: List<VodEpisode> = emptyList()
) {
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()
}

/** Xtream Codes On Demand: movie and series lists, details, episodes and play links. */
class OnDemand(private val http: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun base(s: Source.Xtream) = s.server.trim().trimEnd('/').let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
    }

    private suspend fun call(s: Source.Xtream, action: String, vararg extra: Pair<String, String>): JsonElement {
        val res = http.get("${base(s)}/player_api.php") {
            parameter("username", s.username)
            parameter("password", s.password)
            parameter("action", action)
            extra.forEach { (k, v) -> parameter(k, v) }
        }
        if (!res.status.isSuccess()) throw IllegalStateException("The server answered ${res.status.value}")
        val text = res.bodyAsText()
        return if (text.isBlank()) JsonArray(emptyList()) else json.parseToJsonElement(text)
    }

    suspend fun categories(s: Source.Xtream, kind: VodKind): List<VodCategory> =
        call(s, if (kind == VodKind.MOVIE) "get_vod_categories" else "get_series_categories").objects().mapNotNull { c ->
            val id = c.str("category_id") ?: return@mapNotNull null
            VodCategory(id, c.str("category_name")?.trim().orEmpty().ifBlank { id }, kind)
        }

    /**
     * The whole movie or series list. Read one entry at a time straight from the network:
     * a provider's list can hold 100,000 titles, and reading it in one piece ran the TV box
     * out of memory. Only what the lists need is kept (descriptions come with the details).
     * [onProgress] gets the count so far, every 250 titles. With [categoryId], just that category.
     */
    suspend fun items(s: Source.Xtream, kind: VodKind, categoryId: String? = null, onProgress: (Int) -> Unit = {}): List<VodItem> {
        val out = ArrayList<VodItem>()
        val strings = HashMap<String, String>() // category ids and the like: one copy each
        fun share(v: String) = strings.getOrPut(v) { v }
        val action = if (kind == VodKind.MOVIE) "get_vod_streams" else "get_series"
        http.prepareGet("${base(s)}/player_api.php") {
            parameter("username", s.username)
            parameter("password", s.password)
            parameter("action", action)
            // One category only: a small list that comes back quickly.
            if (categoryId != null) parameter("category_id", categoryId)
        }.execute { res ->
            if (!res.status.isSuccess()) throw IllegalStateException("The server answered ${res.status.value}")
            val channel = res.bodyAsChannel()
            // One entry at a time: the big list never sits in memory as text or as a tree.
            val splitter = JsonArraySplitter { text ->
                val o = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                if (o != null) toItem(o, kind, ::share)?.let { out += it; if (out.size % 250 == 0) onProgress(out.size) }
            }
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = channel.readAvailable(buf, 0, buf.size)
                if (n == -1) break
                if (n > 0) splitter.feed(buf, n)
            }
        }
        return out
    }

    private fun toItem(o: JsonObject, kind: VodKind, share: (String) -> String): VodItem? =
        if (kind == VodKind.MOVIE) {
            val id = o.str("stream_id")
            if (id == null) null else VodItem(
                id = id, kind = VodKind.MOVIE,
                name = o.str("name")?.trim().orEmpty().ifBlank { "Movie $id" },
                categoryId = share(o.str("category_id").orEmpty()),
                poster = o.str("stream_icon")?.takeIf { it.startsWith("http") },
                rating = o.str("rating")?.toDoubleOrNull()?.takeIf { it > 0 },
                year = o.str("year")?.take(4)?.toIntOrNull() ?: yearIn(o.str("name")),
                added = o.str("added")?.toLongOrNull() ?: 0,
                container = o.str("container_extension")?.ifBlank { null }?.let(share),
                tmdbId = o.str("tmdb")?.takeIf { it.isNotBlank() && it != "0" }
            )
        } else {
            val id = o.str("series_id")
            if (id == null) null else VodItem(
                id = id, kind = VodKind.SERIES,
                name = o.str("name")?.trim().orEmpty().ifBlank { "Series $id" },
                categoryId = share(o.str("category_id").orEmpty()),
                poster = o.str("cover")?.takeIf { it.startsWith("http") },
                rating = o.str("rating")?.toDoubleOrNull()?.takeIf { it > 0 },
                year = (o.str("releaseDate") ?: o.str("release_date"))?.take(4)?.toIntOrNull() ?: yearIn(o.str("name")),
                added = o.str("last_modified")?.toLongOrNull() ?: 0,
                tmdbId = o.str("tmdb")?.takeIf { it.isNotBlank() && it != "0" },
                genre = o.str("genre")?.ifBlank { null }?.let(share)
            )
        }

    suspend fun details(s: Source.Xtream, item: VodItem): VodDetails =
        if (item.kind == VodKind.MOVIE) {
            val root = call(s, "get_vod_info", "vod_id" to item.id) as? JsonObject
            val info = root?.get("info") as? JsonObject
            val data = root?.get("movie_data") as? JsonObject
            VodDetails(
                item = item.copy(
                    container = data?.str("container_extension")?.ifBlank { null } ?: item.container,
                    poster = item.poster ?: info?.str("movie_image")?.takeIf { it.startsWith("http") }
                ),
                plot = info?.str("plot")?.ifBlank { null } ?: info?.str("description")?.ifBlank { null },
                backdrop = info?.backdrop(),
                genre = info?.str("genre")?.ifBlank { null },
                cast = info?.str("cast")?.ifBlank { null } ?: info?.str("actors")?.ifBlank { null },
                director = info?.str("director")?.ifBlank { null },
                durationSecs = info?.str("duration_secs")?.toIntOrNull(),
                releaseDate = info?.str("releasedate")?.ifBlank { null } ?: info?.str("release_date")?.ifBlank { null },
                trailerYouTubeId = info?.str("youtube_trailer")?.ifBlank { null },
                tmdbId = info?.str("tmdb_id")?.takeIf { it.isNotBlank() && it != "0" } ?: item.tmdbId
            )
        } else {
            val root = call(s, "get_series_info", "series_id" to item.id) as? JsonObject
            val info = root?.get("info") as? JsonObject
            // "episodes" is { "1": [ … ], "2": [ … ] } on most servers, a plain list on some.
            val eps = when (val e = root?.get("episodes")) {
                is JsonObject -> e.values.flatMap { (it as? JsonArray).orEmpty() }
                is JsonArray -> e.flatMap { (it as? JsonArray) ?: listOf(it) }
                else -> emptyList()
            }.mapNotNull { it as? JsonObject }.mapNotNull { o ->
                val id = o.str("id") ?: return@mapNotNull null
                val ei = o["info"] as? JsonObject
                VodEpisode(
                    id = id,
                    season = o.str("season")?.toIntOrNull() ?: 1,
                    episode = o.str("episode_num")?.toIntOrNull() ?: 0,
                    title = o.str("title")?.trim().orEmpty().ifBlank { "Episode ${o.str("episode_num").orEmpty()}" },
                    container = o.str("container_extension")?.ifBlank { null } ?: "mp4",
                    plot = ei?.str("plot")?.ifBlank { null },
                    image = ei?.str("movie_image")?.takeIf { it.startsWith("http") },
                    durationSecs = ei?.str("duration_secs")?.toIntOrNull()
                )
            }.sortedWith(compareBy({ it.season }, { it.episode }))
            VodDetails(
                item = item,
                plot = info?.str("plot")?.ifBlank { null } ?: item.plot,
                backdrop = info?.backdrop(),
                genre = info?.str("genre")?.ifBlank { null } ?: item.genre,
                cast = info?.str("cast")?.ifBlank { null },
                director = info?.str("director")?.ifBlank { null },
                releaseDate = info?.str("releaseDate")?.ifBlank { null } ?: info?.str("release_date")?.ifBlank { null },
                trailerYouTubeId = info?.str("youtube_trailer")?.ifBlank { null },
                tmdbId = info?.str("tmdb")?.takeIf { it.isNotBlank() && it != "0" } ?: item.tmdbId,
                episodes = eps
            )
        }

    fun movieUrl(s: Source.Xtream, item: VodItem) =
        "${base(s)}/movie/${s.username.encodeURLPathPart()}/${s.password.encodeURLPathPart()}/${item.id}.${item.container ?: "mp4"}"

    fun episodeUrl(s: Source.Xtream, ep: VodEpisode) =
        "${base(s)}/series/${s.username.encodeURLPathPart()}/${s.password.encodeURLPathPart()}/${ep.id}.${ep.container}"

    private fun JsonObject.str(key: String): String? = when (val v = this[key]) {
        is JsonPrimitive -> v.contentOrNull
        else -> null
    }
    private fun JsonObject.backdrop(): String? = when (val b = this["backdrop_path"]) {
        is JsonArray -> b.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> b.contentOrNull
        else -> null
    }?.takeIf { it.startsWith("http") }
    private fun JsonElement.objects(): List<JsonObject> = (this as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    companion object {
        private val TAGS = Regex("""^(\s*(\[[^\]]*\]|\|[^|]*\||[A-Z0-9]{2,4}\s*[-|:]\s+))+""")
        /** Tags and years at the end: " (1999)", " [MULTI-SUB]", " - 2026", " (4K)". */
        private val TRAILING = Regex("""(\s*(\[[^\]]*\]|\([^)]*\)|[-–|:]\s*(19|20)\d\d))+\s*$""")
        private val YEAR = Regex("""(19|20)\d\d""")

        /** "DE - The Matrix (1999)" → "The Matrix"; "Heartland - 2026 [MULTI-SUB]" → "Heartland". */
        fun cleanTitle(name: String): String {
            val t = name.replace(TAGS, "").replace(TRAILING, "").trim().trimEnd('-', '–', '|', ':').trim()
            return t.ifBlank { name.trim() }
        }

        /** The year from the end of a name: "Heartland - 2026 [MULTI-SUB]" → 2026. */
        fun yearIn(name: String?): Int? = name?.let { n -> TRAILING.find(n)?.value?.let { YEAR.findAll(it).lastOrNull()?.value?.toIntOrNull() } }

        private val LANG_TAG = Regex("""^\s*(?:[\[(]\s*([A-Za-z][A-Za-z0-9 .\-]{0,14}?)\s*[\])]|([A-Z]{2,3}(?:-[A-Z]{2,5})?)\s*[|:\-])""")

        /**
         * The language or region a category is tagged with: "[AR] Action" → "AR", "AF | Films" →
         * "AF", "[MULTI-LANG] Netflix" → "MULTI-LANG". Null when the name has no such tag.
         */
        fun categoryTag(name: String): String? =
            LANG_TAG.find(name)?.let { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).trim().uppercase().ifBlank { null } }

        private const val FROM = "àáâãäåāăąçćčďèéêëēėęěìíîïīįłñńňòóôõöøōőřśšşťùúûüūůűųýÿžźż"
        private const val TO = "aaaaaaaaacccdeeeeeeeeiiiiiilnnnoooooooorssstuuuuuuuuyyzzz"
        fun normalize(s: String): String = buildString {
            for (c in s.lowercase()) {
                val i = FROM.indexOf(c)
                when {
                    i >= 0 -> append(TO[i])
                    c == 'ß' -> append("ss")
                    c.isLetterOrDigit() -> append(c)
                }
            }
        }
    }
}

/**
 * Cuts a JSON array arriving in pieces into its entries: `[{…},{…}]` → each `{…}` as text.
 * Works on bytes (all JSON structure is plain ASCII), so UTF-8 letters are never split.
 */
internal class JsonArraySplitter(private val onEntry: (String) -> Unit) {
    private var depth = 0
    private var inString = false
    private var escaped = false
    private var current = ByteArrayBuilder()

    fun feed(bytes: ByteArray, count: Int) {
        for (i in 0 until count) {
            val b = bytes[i]
            if (depth >= 2 || (depth == 1 && b == '{'.code.toByte())) current.add(b)
            if (inString) {
                when {
                    escaped -> escaped = false
                    b == '\\'.code.toByte() -> escaped = true
                    b == '"'.code.toByte() -> inString = false
                }
                continue
            }
            when (b) {
                '"'.code.toByte() -> inString = true
                '['.code.toByte(), '{'.code.toByte() -> depth++
                ']'.code.toByte(), '}'.code.toByte() -> {
                    depth--
                    if (depth == 1 && b == '}'.code.toByte()) { onEntry(current.text()); current = ByteArrayBuilder() }
                }
            }
        }
    }

    private class ByteArrayBuilder {
        private var data = ByteArray(1024)
        private var size = 0
        fun add(b: Byte) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = b
        }
        fun text(): String = data.decodeToString(0, size)
    }
}
