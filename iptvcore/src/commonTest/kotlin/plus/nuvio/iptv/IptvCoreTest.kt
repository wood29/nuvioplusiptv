package plus.nuvio.iptv

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvCoreTest {
    private val src = Source.M3u("p", "Test", "http://x/list.m3u")

    @Test fun m3u() {
        val text = """
            #EXTM3U url-tvg="http://g/guide.xml"
            #EXTINF:-1 tvg-id="orf1.at" tvg-name="ORF 1" tvg-logo="http://l/orf1.png" group-title="Austria, HD",ORF 1 HD
            #EXTVLCOPT:http-user-agent=Box/1.0
            http://s/1.ts
            #EXTINF:-1 tvg-id="orf1.at" group-title="Austria, HD",ORF 1 SD
            http://s/2.ts
            #EXTINF:-1,Plain Channel
            #EXTGRP:Misc
            http://s/3.m3u8
        """.trimIndent()
        val l = M3uParser.parse(text, src)
        assertEquals("http://g/guide.xml", l.playlistGuideUrl)
        assertEquals(3, l.channels.size)
        val a = l.channels[0]
        assertEquals("ORF 1 HD", a.name); assertEquals("orf1.at", a.guideId); assertEquals("Austria, HD", l.groups[0].name)
        assertEquals("Box/1.0", a.headers["User-Agent"])
        assertEquals("orf1.at#2", l.channels[1].id)
        assertEquals("Misc", l.groups.first { it.id == l.channels[2].groupId }.name)
        assertTrue(l.channels[1].headers.isEmpty())
    }

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="ORF2.at"><display-name>ORF 2</display-name></channel>
          <programme start="20261112190000 +0100" stop="20261112193000 +0100" channel="ORF2.at">
            <title lang="de">Zeit im Bild</title><desc>News &amp; weather</desc><category>News</category>
          </programme>
          <programme start="20261112201500 +0100" stop="20261112211000 +0100" channel="ORF2.at">
            <title>Der Bergdoktor</title><sub-title>Alte Wunden</sub-title>
            <episode-num system="xmltv_ns">17.4.</episode-num><icon src="http://i/b.jpg"/>
            <credits><actor>Hans Sigl</actor><actor>Heiko Ruprecht</actor></credits><date>2026</date>
          </programme>
          <programme start="20261112193000 +0100" stop="20261112201500 +0100" channel="ORF2.at"><title>Bundesland heute</title></programme>
        </tv>
    """.trimIndent()

    private fun guideFrom(text: String, chunk: Int): Guide {
        val list = ArrayList<Programme>()
        val p = XmltvParser { list += it }
        text.chunked(chunk).forEach { p.feed(it) }
        p.finish()
        return Guide(list, p.channelNames)
    }

    @Test fun xmltvTimesAndFields() {
        // 19:00 Vienna (UTC+1) = 18:00 UTC
        assertEquals(1_794_506_400_000L, XmltvParser.parseTime("20261112190000 +0100"))
        listOf(7, 50, 10_000).forEach { size ->
            val g = guideFrom(xml, size)
            val ch = Channel("1", "p", "AT: ORF 2 HD", 2, "g", streamUrl = "x")   // no tvg-id: matched by name
            val all = g.listings(ch)
            assertEquals(3, all.size, "chunk $size: ${g.debug()}")
            val berg = all[2]
            assertEquals("S18 E5 · Alte Wunden", berg.episode)
            assertEquals(listOf("Hans Sigl", "Heiko Ruprecht"), berg.people)
            assertEquals("http://i/b.jpg", berg.icon); assertEquals(2026, berg.year); assertEquals(55, berg.durationMinutes)
            assertEquals("News & weather", all[0].description)
        }
    }

    @Test fun nowNextLater() {
        val g = guideFrom(xml, 10_000)
        val ch = Channel("1", "p", "ORF2", 2, "g", guideId = "orf2.at", streamUrl = "x")
        val at1910 = XmltvParser.parseTime("20261112191000 +0100")!!
        val nn = g.nowNext(ch, at1910)
        assertEquals("Zeit im Bild", nn.now?.title); assertEquals("Bundesland heute", nn.next?.title); assertEquals("Der Bergdoktor", nn.later?.title)
        val gap = g.nowNext(ch, XmltvParser.parseTime("20261112180000 +0100")!!)
        assertNull(gap.now); assertEquals("Zeit im Bild", gap.next?.title)
        assertEquals(1, g.alsoShown("der bergdoktor", at1910, listOf(ch)).size)
    }

    @Test fun xtreamLoginLineupAndCatchup() = runBlocking {
        val engine = MockEngine { req ->
            val body = when (req.url.parameters["action"]) {
                null -> """{"user_info":{"auth":1,"status":"Active","exp_date":"1800000000"}}"""
                "get_live_categories" -> """[{"category_id":"5","category_name":"Austria"}]"""
                "get_live_streams" -> """[{"num":2,"name":"ORF 2 HD","stream_id":202,"stream_icon":"","epg_channel_id":"ORF2.at","category_id":"5","tv_archive":1,"tv_archive_duration":"7"}]"""
                else -> "[]"
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = XtreamClient(HttpClient(engine))
        val s = Source.Xtream("x", "Prov", "prov.example:8080/", "me", "p w")
        assertEquals(1_800_000_000L, client.login(s))
        val l = client.lineup(s)
        val ch = l.channels.single()
        assertEquals("http://prov.example:8080/live/me/p%20w/202.ts", ch.streamUrl)
        assertEquals(7, ch.catchupDays); assertNull(ch.logo); assertEquals("Austria", l.groups.single().name)
        assertNotNull(l.playlistGuideUrl)
        // 20:15 Vienna show; the server runs on Vienna time (+60 min)
        val start = XmltvParser.parseTime("20261112201500 +0100")!!
        assertEquals("http://prov.example:8080/timeshift/me/p%20w/55/2026-11-12:20-15/202.ts", client.catchupUrl(s, 202, start, 55, 60))
    }
}
