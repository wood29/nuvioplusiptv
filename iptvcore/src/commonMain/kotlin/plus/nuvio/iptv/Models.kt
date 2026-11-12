/*
 * Nuvio Plus IPTV core.
 * Written from scratch for Nuvio Plus; not derived from Nuvio or any GPL code, so it can be
 * reused in a separate IPTV-only app later. See iptvcore/LICENSE.
 */
package plus.nuvio.iptv

/** Where the channels come from: an M3U playlist link, or an Xtream Codes login. */
sealed interface Source {
    val id: String
    val name: String

    data class M3u(
        override val id: String,
        override val name: String,
        val url: String,
        /** An XMLTV guide link; null = use the playlist's own url-tvg / x-tvg-url if it has one. */
        val guideUrl: String? = null,
        val userAgent: String? = null
    ) : Source

    data class Xtream(
        override val id: String,
        override val name: String,
        val server: String,
        val username: String,
        val password: String,
        /** "ts" or "m3u8" for live streams. */
        val liveFormat: String = "ts"
    ) : Source
}

data class Group(val id: String, val name: String)

data class Channel(
    /** Stable within a source: the Xtream stream id, or the playlist's tvg-id / name. */
    val id: String,
    val sourceId: String,
    val name: String,
    val number: Int,
    val groupId: String,
    val logo: String? = null,
    /** The guide's channel id (tvg-id / epg_channel_id). */
    val guideId: String? = null,
    val streamUrl: String,
    /** Days of catch-up the provider keeps (0 = none). */
    val catchupDays: Int = 0,
    /** Xtream stream id, for catch-up links. */
    val xtreamStreamId: Int? = null,
    val headers: Map<String, String> = emptyMap()
)

data class Programme(
    val guideId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val episode: String? = null,
    val icon: String? = null,
    val year: Int? = null,
    val people: List<String> = emptyList()
) {
    fun isOnAt(timeMs: Long) = timeMs in startMs until stopMs
    val durationMinutes: Long get() = (stopMs - startMs) / 60_000L
}

/** What's on now and next on one channel. */
data class NowNext(val now: Programme?, val next: Programme?, val later: Programme?)

/** Everything the app shows for one source. */
data class Lineup(
    val source: Source,
    val groups: List<Group>,
    val channels: List<Channel>,
    /** Guide link found in the playlist itself (url-tvg). */
    val playlistGuideUrl: String? = null
) {
    fun channelsIn(groupId: String) = channels.filter { it.groupId == groupId }
}
