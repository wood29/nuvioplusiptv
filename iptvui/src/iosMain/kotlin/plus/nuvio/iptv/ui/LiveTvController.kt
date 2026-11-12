/*
 * Nuvio Plus Live TV screens: written from scratch (see iptvui/LICENSE).
 */
package plus.nuvio.iptv.ui

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import plus.nuvio.iptv.Channel
import plus.nuvio.iptv.Guide
import plus.nuvio.iptv.LiveTvEngine
import plus.nuvio.iptv.Programme
import plus.nuvio.iptv.Source

internal fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

/** Keeps the source, the picked group, the search and what's playing for the Live TV tab. */
class LiveTvController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = HttpClient(Darwin) {
        install(HttpTimeout) { requestTimeoutMillis = 120_000; connectTimeoutMillis = 15_000 }
    }
    val engine = LiveTvEngine(http, ::nowMs)
    val state = engine.state

    private val _group = MutableStateFlow<String?>(FAVOURITES_OFF)
    val group: StateFlow<String?> = _group.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _playing = MutableStateFlow<Channel?>(null)
    val playing: StateFlow<Channel?> = _playing.asStateFlow()
    private val _favourites = MutableStateFlow(store.stringArrayForKey(KEY_FAVS)?.map { it.toString() }?.toSet() ?: emptySet())
    val favourites: StateFlow<Set<String>> = _favourites.asStateFlow()
    /** Ticks every 30 s so "now" and the progress bars move on. */
    private val _clock = MutableStateFlow(nowMs())
    val clock: StateFlow<Long> = _clock.asStateFlow()

    var player: LivePlayer? = null
        private set

    init {
        scope.launch { while (true) { delay(30_000); _clock.value = nowMs() } }
        savedSource()?.let { load(it) }
    }

    private val _hasSource = MutableStateFlow(savedSource() != null)
    /** A provider is set up on this device. */
    val hasSource: StateFlow<Boolean> = _hasSource.asStateFlow()

    fun connect(source: Source) {
        save(source)
        _hasSource.value = true
        load(source)
    }

    fun retry() { savedSource()?.let { load(it) } }

    fun signOut() {
        stop()
        listOf(KEY_KIND, KEY_A, KEY_B, KEY_C, KEY_D, KEY_LAST).forEach { store.removeObjectForKey(it) }
        engine.reset()
        _hasSource.value = false
        _group.value = FAVOURITES_OFF
    }

    private fun load(source: Source) {
        scope.launch {
            engine.load(source)
            val lineup = engine.state.value.lineup ?: return@launch
            // Start where you left off.
            val last = store.stringForKey(KEY_LAST)
            if (_playing.value == null) lineup.channels.firstOrNull { it.id == last }?.let { _playing.value = it }
        }
    }

    fun pickGroup(id: String?) { _group.value = id }
    fun search(q: String) { _query.value = q }

    fun play(channel: Channel) {
        val p = player ?: LivePlayers.create().also { player = it } ?: return
        _playing.value = channel
        store.setObject(channel.id, KEY_LAST)
        p.play(channel.streamUrl, channel.headers)
    }

    fun playArchive(channel: Channel, programme: Programme) {
        val url = engine.catchupUrl(channel, programme) ?: return
        val p = player ?: LivePlayers.create().also { player = it } ?: return
        _playing.value = channel
        p.play(url, channel.headers)
    }

    fun zap(step: Int, list: List<Channel>) {
        val current = _playing.value ?: return
        val i = list.indexOfFirst { it.id == current.id }
        if (i < 0 || list.isEmpty()) return
        play(list[(i + step + list.size) % list.size])
    }

    fun stop() { player?.stop(); _playing.value = null }

    fun toggleFavourite(channel: Channel) {
        val now = _favourites.value.toMutableSet()
        if (!now.add(channel.id)) now.remove(channel.id)
        _favourites.value = now
        store.setObject(now.toList(), KEY_FAVS)
    }

    /** The channels shown for the picked group and search. */
    fun visible(channels: List<Channel>, group: String?, query: String, favs: Set<String>): List<Channel> {
        val q = query.trim()
        if (q.isNotEmpty()) return channels.filter { it.name.contains(q, ignoreCase = true) }
        return when (group) {
            FAVOURITES -> channels.filter { it.id in favs }
            null, FAVOURITES_OFF -> channels
            else -> channels.filter { it.groupId == group }
        }
    }

    fun guide(): Guide = state.value.guide

    // ---- the saved source (this device only) ----
    private fun save(s: Source) {
        when (s) {
            is Source.Xtream -> {
                store.setObject("xtream", KEY_KIND); store.setObject(s.server, KEY_A)
                store.setObject(s.username, KEY_B); store.setObject(s.password, KEY_C); store.removeObjectForKey(KEY_D)
            }
            is Source.M3u -> {
                store.setObject("m3u", KEY_KIND); store.setObject(s.url, KEY_A)
                s.guideUrl?.let { store.setObject(it, KEY_D) } ?: store.removeObjectForKey(KEY_D)
            }
        }
    }

    private fun savedSource(): Source? = when (store.stringForKey(KEY_KIND)) {
        "xtream" -> {
            val server = store.stringForKey(KEY_A); val user = store.stringForKey(KEY_B); val pass = store.stringForKey(KEY_C)
            if (server != null && user != null && pass != null) Source.Xtream("main", "My provider", server, user, pass) else null
        }
        "m3u" -> store.stringForKey(KEY_A)?.let { Source.M3u("main", "My playlist", it, guideUrl = store.stringForKey(KEY_D)) }
        else -> null
    }

    companion object {
        const val FAVOURITES = "__favourites"
        /** "All channels". */
        const val FAVOURITES_OFF = "__all"
        private val store = NSUserDefaults.standardUserDefaults
        private const val KEY_KIND = "np_live_kind"
        private const val KEY_A = "np_live_a"
        private const val KEY_B = "np_live_b"
        private const val KEY_C = "np_live_c"
        private const val KEY_D = "np_live_d"
        private const val KEY_LAST = "np_live_last"
        private const val KEY_FAVS = "np_live_favs"
    }
}
