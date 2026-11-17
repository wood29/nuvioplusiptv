package plus.nuvio.iptv

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class OnDemandTest {
    private val s = Source.Xtream("x", "Prov", "prov.example:8080", "me", "pw")

    private val od = OnDemand(HttpClient(MockEngine { req ->
        val body = when (req.url.parameters["action"]) {
            "get_vod_categories" -> """[{"category_id":"1","category_name":"Action"}]"""
            "get_vod_streams" -> """[{"stream_id":7,"name":"DE - The Matrix (1999)","stream_icon":"http://i/m.jpg","rating":"8.7","added":"1700000000","category_id":"1","container_extension":"mkv","tmdb":"603"}]"""
            "get_vod_info" -> """{"info":{"plot":"Neo.","backdrop_path":["http://i/b.jpg"],"duration_secs":"8160","genre":"Sci-Fi"},"movie_data":{"stream_id":7,"container_extension":"mkv"}}"""
            "get_series" -> """[{"series_id":9,"name":"Dark","cover":"http://i/d.jpg","category_id":"2","releaseDate":"2017-12-01"}]"""
            "get_series_info" -> """{"info":{"plot":"Time."},"episodes":{"2":[{"id":"22","episode_num":1,"title":"S2E1","container_extension":"mp4","season":2}],"1":[{"id":"12","episode_num":2,"title":"S1E2","container_extension":"mkv","season":1},{"id":"11","episode_num":1,"title":"S1E1","container_extension":"mkv","season":1}]}}"""
            else -> "[]"
        }
        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }))

    @Test fun movies() = runBlocking {
        assertEquals("Action", od.categories(s, VodKind.MOVIE).single().name)
        val m = od.items(s, VodKind.MOVIE).single()
        assertEquals("The Matrix", m.title); assertEquals(1999, m.year); assertEquals("603", m.tmdbId)
        val d = od.details(s, m)
        assertEquals("Neo.", d.plot); assertEquals("http://i/b.jpg", d.backdrop); assertEquals(8160, d.durationSecs)
        assertEquals("http://prov.example:8080/movie/me/pw/7.mkv", od.movieUrl(s, d.item))
    }

    @Test fun series() = runBlocking {
        val sr = od.items(s, VodKind.SERIES).single()
        assertEquals(2017, sr.year)
        val d = od.details(s, sr)
        assertEquals(listOf("11", "12", "22"), d.episodes.map { it.id })
        assertEquals(listOf(1, 2), d.seasons)
        assertEquals("http://prov.example:8080/series/me/pw/11.mkv", od.episodeUrl(s, d.episodes[0]))
    }

    @Test fun splitter() {
        val got = mutableListOf<String>()
        val sp = JsonArraySplitter { got += it }
        val text = """[{"a":"x}{","b":[1,{"c":2}]} , {"d":"é\"q"}]""".encodeToByteArray()
        // In small pieces, as from the network.
        text.toList().chunked(3).forEach { sp.feed(it.toByteArray(), it.size) }
        assertEquals(listOf("""{"a":"x}{","b":[1,{"c":2}]}""", """{"d":"é\"q"}"""), got)
    }

    @Test fun titles() {
        assertEquals("Inception", OnDemand.cleanTitle("[4K] EN - Inception [2010]"))
        assertEquals("Heartland", OnDemand.cleanTitle("Heartland - 2026 [MULTI-SUB]"))
        assertEquals(2026, OnDemand.yearIn("Heartland - 2026 [MULTI-SUB]"))
        assertEquals("heidi", OnDemand.normalize("Heidi!")); assertEquals("strassea", OnDemand.normalize("Straße Ä"))
    }
}
