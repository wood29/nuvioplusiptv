/*
 * Nuvio Plus IPTV core: written from scratch (see iptvcore/LICENSE).
 */
package plus.nuvio.iptv

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Live TV engine the iPhone, iPad and Apple TV screens talk to: load a source, keep its
 * channels and guide, answer "what's on".
 *
 * Playback is not here: the screens hand [Channel.streamUrl] (+ [Channel.headers]) to the
 * player (mpv on Apple devices).
 */
class LiveTvEngine(private val http: HttpClient, private val clock: () -> Long) {

    data class State(
        val loading: Boolean = false,
        val error: String? = null,
        val lineup: Lineup? = null,
        val guide: Guide = Guide(emptyList()),
        val guideLoading: Boolean = false
    )

    private val xtream = XtreamClient(http)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun load(source: Source) {
        _state.value = State(loading = true)
        val lineup = try {
            when (source) {
                is Source.Xtream -> { xtream.login(source); xtream.lineup(source) }
                is Source.M3u -> M3uParser.parse(
                    http.prepareGet(source.url) { source.userAgent?.let { header("User-Agent", it) } }.execute { it.bodyAsText() },
                    source
                )
            }
        } catch (e: Exception) {
            _state.value = State(error = e.message ?: "Couldn't load the channels")
            return
        }
        _state.value = State(lineup = lineup, guideLoading = true)
        val guideUrl = (source as? Source.M3u)?.guideUrl ?: lineup.playlistGuideUrl
        val guide = guideUrl?.let { runCatching { loadGuide(it) }.getOrNull() } ?: Guide(emptyList())
        _state.value = _state.value.copy(guide = guide, guideLoading = false)
    }

    /** Reads an XMLTV guide line by line (keeps memory low), from yesterday to a week ahead. */
    suspend fun loadGuide(url: String): Guide {
        val now = clock()
        val programmes = ArrayList<Programme>()
        val parser = XmltvParser(fromMs = now - DAY, untilMs = now + 7 * DAY) { programmes += it }
        http.prepareGet(url).execute { res ->
            val body = res.bodyAsChannel()
            while (true) {
                val line = body.readUTF8Line() ?: break
                parser.feed(line)
                parser.feed("\n")
            }
        }
        parser.finish()
        return Guide(programmes, parser.channelNames)
    }

    fun nowNext(channel: Channel): NowNext = state.value.guide.nowNext(channel, clock())

    fun catchupUrl(channel: Channel, programme: Programme, serverOffsetMinutes: Int = 0): String? {
        val src = state.value.lineup?.source as? Source.Xtream ?: return null
        val id = channel.xtreamStreamId ?: return null
        if (channel.catchupDays <= 0 || programme.startMs < clock() - channel.catchupDays * DAY) return null
        return xtream.catchupUrl(src, id, programme.startMs, programme.durationMinutes.toInt().coerceAtLeast(1), serverOffsetMinutes)
    }

    private companion object { const val DAY = 86_400_000L }
}
