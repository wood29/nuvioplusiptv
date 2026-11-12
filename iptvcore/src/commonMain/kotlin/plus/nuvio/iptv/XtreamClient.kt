/*
 * Nuvio Plus IPTV core: written from scratch (see iptvcore/LICENSE).
 */
package plus.nuvio.iptv

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Xtream Codes "player_api.php": live categories, live channels, short guide and catch-up links. */
class XtreamClient(private val http: HttpClient) {

    class LoginException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun base(s: Source.Xtream) = s.server.trim().trimEnd('/').let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
    }

    private suspend fun call(s: Source.Xtream, action: String?, vararg extra: Pair<String, String>): JsonElement {
        val res = http.get("${base(s)}/player_api.php") {
            parameter("username", s.username)
            parameter("password", s.password)
            if (action != null) parameter("action", action)
            extra.forEach { (k, v) -> parameter(k, v) }
        }
        if (!res.status.isSuccess()) throw LoginException("The server answered ${res.status.value}")
        return json.parseToJsonElement(res.bodyAsText())
    }

    /** Checks the login. Returns the account's expiry (epoch seconds) if the server tells it. */
    suspend fun login(s: Source.Xtream): Long? {
        val info = (call(s, null) as? JsonObject)?.get("user_info") as? JsonObject
            ?: throw LoginException("This doesn't look like an Xtream server")
        if (info.str("auth") == "0") throw LoginException("Wrong username or password")
        val status = info.str("status")
        if (status != null && !status.equals("Active", ignoreCase = true)) throw LoginException("Account is $status")
        return info.str("exp_date")?.toLongOrNull()
    }

    suspend fun lineup(s: Source.Xtream): Lineup {
        val cats = (call(s, "get_live_categories") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val groups = cats.mapNotNull { c ->
            val id = c.str("category_id") ?: return@mapNotNull null
            Group(id, c.str("category_name") ?: id)
        }
        val streams = (call(s, "get_live_streams") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val channels = streams.mapIndexedNotNull { i, o ->
            val streamId = o.str("stream_id")?.toIntOrNull() ?: return@mapIndexedNotNull null
            Channel(
                id = streamId.toString(),
                sourceId = s.id,
                name = o.str("name")?.trim().orEmpty().ifBlank { "Channel $streamId" },
                number = o.str("num")?.toIntOrNull() ?: (i + 1),
                groupId = o.str("category_id") ?: "",
                logo = o.str("stream_icon")?.ifBlank { null },
                guideId = o.str("epg_channel_id")?.ifBlank { null },
                streamUrl = liveUrl(s, streamId),
                catchupDays = if (o.str("tv_archive") == "1") o.str("tv_archive_duration")?.toIntOrNull() ?: 0 else 0,
                xtreamStreamId = streamId
            )
        }
        return Lineup(s, groups, channels, playlistGuideUrl = guideUrl(s))
    }

    fun liveUrl(s: Source.Xtream, streamId: Int) =
        "${base(s)}/live/${s.username.encodeURLPathPart()}/${s.password.encodeURLPathPart()}/$streamId.${s.liveFormat}"

    /** The provider's full XMLTV guide. */
    fun guideUrl(s: Source.Xtream) =
        "${base(s)}/xmltv.php?username=${s.username.encodeURLPathPart()}&password=${s.password.encodeURLPathPart()}"

    /**
     * A past show from the archive. [startMs] is the show's start; times are sent in the
     * server's local time, which Xtream servers expect as "yyyy-MM-dd:HH-mm".
     */
    fun catchupUrl(s: Source.Xtream, streamId: Int, startMs: Long, minutes: Int, serverOffsetMinutes: Int): String {
        val t = formatServerTime(startMs + serverOffsetMinutes * 60_000L)
        return "${base(s)}/timeshift/${s.username.encodeURLPathPart()}/${s.password.encodeURLPathPart()}/$minutes/$t/$streamId.ts"
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    companion object {
        /** epoch ms (already shifted to the server's zone) → "2026-11-12:20-15". */
        internal fun formatServerTime(ms: Long): String {
            val totalMin = ms.floorDiv(60_000L)
            val days = totalMin.floorDiv(1440L)
            val minOfDay = totalMin - days * 1440L
            val (y, m, d) = civilFromDays(days)
            fun two(n: Long) = n.toString().padStart(2, '0')
            return "$y-${two(m.toLong())}-${two(d.toLong())}:${two(minOfDay / 60)}-${two(minOfDay % 60)}"
        }

        private fun civilFromDays(z0: Long): Triple<Long, Int, Int> {
            val z = z0 + 719_468L
            val era = (if (z >= 0) z else z - 146_096L) / 146_097L
            val doe = z - era * 146_097L
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = yoe + era * 400 + if (m <= 2) 1 else 0
            return Triple(y, m, d)
        }
    }
}
