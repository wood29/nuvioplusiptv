/*
 * avonioTV core: written from scratch for avonioTV (see core/LICENSE).
 */
package app.avoniotv.core

import io.ktor.http.isSuccess
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
 * The Live TV engine the screens talk to: load a source, keep its channels and guide, answer
 * "what's on".
 *
 * Playback is not here: the screens hand [Channel.streamUrl] (+ [Channel.headers]) to the
 * player (Media3 on Android TV, mpv on Apple devices).
 */
class LiveTvEngine(
    private val http: HttpClient,
    private val clock: () -> Long,
    /** How much of the guide to keep: some of the past, and this far ahead (a TV box keeps less). */
    private val guidePastMs: Long = DAY,
    private val guideAheadMs: Long = 7 * DAY
) {

    data class State(
        val loading: Boolean = false,
        val error: String? = null,
        val lineup: Lineup? = null,
        val guide: Guide = Guide(emptyList()),
        val guideLoading: Boolean = false,
        /** Where the guide came from, and when (for Settings › Guide). */
        val guideUrl: String? = null,
        val guideLoadedAt: Long = 0,
        val guideError: String? = null
    )

    private val xtream = XtreamClient(http)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Forget the source (signed out). */
    fun reset() { rawLineup = null; _state.value = State() }

    /**
     * Where to keep a copy of the guide on the device. With it, the guide appears straight away
     * after a start, and is read from the provider again only when the copy is over 6 hours old.
     */
    /** Where the guide is kept between starts (a file on the TV box, the app's folder on iPad). */
    @kotlin.concurrent.Volatile var guideStore: GuideStore? = null
    @kotlin.concurrent.Volatile var lineupStore: LineupStore? = null

    /** The provider's list as it came (before "Sort your channels"). */
    @kotlin.concurrent.Volatile var rawLineup: Lineup? = null
        private set

    /**
     * Your "Sort your channels" answers (null = the provider's order). Setting it sorts the list
     * on screen again at once; the guide stays as it is.
     */
    @kotlin.concurrent.Volatile var sortPlan: ChannelSorter.Plan? = null
        private set
    /** Groups you've hidden: their channels are never moved up by the sorting. */
    @kotlin.concurrent.Volatile var sortSkipGroups: Set<String> = emptySet()
        private set

    /** Sets your sorting (and hidden groups) and shows the list sorted again at once. */
    fun setSort(plan: ChannelSorter.Plan?, skipGroups: Set<String>) {
        sortPlan = plan; sortSkipGroups = skipGroups
        rawLineup?.let { raw -> _state.value = _state.value.copy(lineup = ordered(ChannelSorter.apply(raw, plan, skipGroups))) }
    }

    /** Your order of the groups (ids, first to last; set on the computer page). Others follow in the provider's order. */
    @kotlin.concurrent.Volatile var groupOrder: List<String> = emptyList()
        private set

    fun setGroupOrder(order: List<String>) {
        groupOrder = order
        rawLineup?.let { raw -> _state.value = _state.value.copy(lineup = ordered(ChannelSorter.apply(raw, sortPlan, sortSkipGroups))) }
    }

    private fun ordered(l: Lineup): Lineup {
        if (groupOrder.isEmpty()) return l
        val at = groupOrder.withIndex().associate { it.value to it.index }
        val n = l.groups.size
        return l.copy(groups = l.groups.withIndex().sortedBy { (i, g) -> at[g.id] ?: (groupOrder.size + i) }.map { it.value }.also { check(it.size == n) })
    }

    private fun arranged(l: Lineup): Lineup { rawLineup = l; return ordered(ChannelSorter.apply(l, sortPlan, sortSkipGroups)) }

    /** A guide link of your own, used instead of the provider's (null = the provider's). */
    @kotlin.concurrent.Volatile var guideOverride: String? = null

    private suspend fun lineupOf(source: Source): Lineup = when (source) {
        is Source.Xtream -> { xtream.login(source); xtream.lineup(source) }
        is Source.M3u -> M3uParser.parse(
            http.prepareGet(source.url) { source.userAgent?.let { header("User-Agent", it) } }.execute { it.bodyAsText() },
            source
        )
    }

    /**
     * Loads the channels of [source] and of any [extras] (more providers), as one list. Channels of
     * an added provider keep their own ids behind a prefix, numbers continue after the first
     * provider's, and a group with the same name gets the provider's name after it.
     */
    suspend fun load(source: Source, extras: List<Source> = emptyList()) {
        // The channels saved last time show at once (with the saved guide); the provider's fresh
        // list replaces them when it arrives.
        val saved = runCatching { lineupStore?.read(source, extras) }.getOrNull()?.takeIf { it.channels.isNotEmpty() }
        if (saved != null) {
            showWithSavedGuide(saved)
            val fresh = try { fetchLineup(source, extras) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            if (fresh != null) {
                runCatching { lineupStore?.write(fresh) }
                _state.value = _state.value.copy(lineup = arranged(fresh))
            }
            val st = _state.value
            if (st.guide.all().none() || clock() - st.guideLoadedAt >= GUIDE_FRESH_MS) refreshGuide()
            return
        }
        _state.value = State(loading = true)
        val lineup = try { fetchLineup(source, extras) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            _state.value = State(error = e.message ?: "Couldn't load the channels")
            return
        }
        // Show first; saving the list for next time comes after (it can take a moment on big lists).
        val fresh = showWithSavedGuide(lineup)
        runCatching { lineupStore?.write(lineup) }
        if (fresh) return
        refreshGuide()
    }

    /** Shows [lineup] with the saved guide; true when that guide is fresh enough (no download needed). */
    private fun showWithSavedGuide(lineup: Lineup): Boolean {
        _state.value = State(lineup = arranged(lineup), guideLoading = true)
        val url = guideKey(lineup)
        val saved = url?.let { runCatching { guideStore?.read(it, clock()) }.getOrNull() }
        if (saved != null) {
            _state.value = _state.value.copy(guide = saved.first, guideLoadedAt = saved.second, guideUrl = url.substringBefore("|"), guideLoading = false)
            return clock() - saved.second < GUIDE_FRESH_MS
        }
        return false
    }

    private suspend fun fetchLineup(source: Source, extras: List<Source>): Lineup {
        val first = lineupOf(source)
        var lineup = first
        if (extras.isNotEmpty()) {
            val groups = first.groups.toMutableList()
            val channels = first.channels.toMutableList()
            val failed = LinkedHashMap<String, String>()
            val names = first.groups.mapTo(HashSet()) { it.name.lowercase() }
            var top = ((first.channels.maxOfOrNull { it.number } ?: 0) / 1000 + 1) * 1000
            for (x in extras) {
                val l = try { lineupOf(x) } catch (e: Exception) { failed[x.id] = e.message ?: "Couldn't load"; continue }
                val p = x.id + ":"
                l.groups.forEach { g ->
                    val n = if (g.name.lowercase() in names) "${g.name} · ${x.name}" else g.name
                    groups += Group(p + g.id, n); names += n.lowercase()
                }
                l.channels.forEach { c -> channels += c.copy(id = p + c.id, groupId = p + c.groupId, number = top + c.number) }
                top = ((channels.maxOfOrNull { it.number } ?: top) / 1000 + 1) * 1000
            }
            lineup = first.copy(groups = groups, channels = channels, sources = listOf(source) + extras.filter { it.id !in failed }, failed = failed)
        }
        return lineup
    }

    /** The guide link in use: yours, the playlist's, or the provider's. */
    fun guideUrlFor(lineup: Lineup): String? =
        guideOverride?.takeIf { it.isNotBlank() } ?: (lineup.source as? Source.M3u)?.guideUrl ?: lineup.playlistGuideUrl

    /** Every guide to read: the first provider's (or yours), then each added Xtream provider's. */
    private fun guideUrls(lineup: Lineup): List<String> =
        (listOfNotNull(guideUrlFor(lineup)) + lineup.sources.drop(1).mapNotNull { (it as? Source.Xtream)?.let(xtream::guideUrl) ?: (it as? Source.M3u)?.guideUrl }).distinct()

    /** What the saved copy is filed under (all the guide links together). */
    private fun guideKey(lineup: Lineup): String? = guideUrls(lineup).takeIf { it.isNotEmpty() }?.joinToString("|")

    /** Loads the guide again (Settings › Guide › Refresh, or a new guide link). */
    suspend fun refreshGuide() {
        val lineup = _state.value.lineup ?: return
        val urls = guideUrls(lineup)
        val url = guideKey(lineup)
        if (url == null) { _state.value = _state.value.copy(guideLoading = false, guideUrl = null, guideError = "No guide link"); return }
        _state.value = _state.value.copy(guideLoading = true, guideUrl = urls.first(), guideError = null)
        val result = runCatching {
            if (urls.size == 1) loadGuide(urls.first(), lineup.channels)
            else {
                // Several providers: each guide for its own channels, then one guide of them all.
                val parts = urls.mapNotNull { u -> runCatching { loadGuide(u, lineup.channels) }.getOrNull() }
                if (parts.isEmpty()) throw IllegalStateException("The guides couldn't be loaded")
                Guide(parts.flatMap { it.all().toList() }, parts.fold(emptyMap()) { acc, g -> acc + g.channelNames })
            }
        }
        // Stopped half-way (the app closed, a newer load took over): not an error to show.
        result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) { _state.value = _state.value.copy(guideLoading = false); throw it } }
        result.getOrNull()?.let { g -> runCatching { guideStore?.write(url, g, clock()) } }
        _state.value = _state.value.copy(
            guide = result.getOrNull() ?: _state.value.guide,
            guideLoading = false,
            guideLoadedAt = if (result.isSuccess) clock() else _state.value.guideLoadedAt,
            guideError = result.exceptionOrNull()?.let { it.message ?: "The guide couldn't be loaded" }
        )
    }

    /** Reads an XMLTV guide line by line (keeps memory low), from yesterday to a week ahead. */
    suspend fun loadGuide(url: String, channels: List<Channel>? = null): Guide {
        val now = clock()
        val programmes = ArrayList<Programme>()
        // Only listings for channels in the lineup: a provider's guide covers thousands of
        // channels you don't have, and keeping them all ran a TV box out of memory.
        val ids = channels?.mapNotNullTo(HashSet()) { it.guideId?.lowercase() }
        val names = channels?.mapTo(HashSet()) { Guide.normalize(it.name) }
        val keep: ((String, List<String>) -> Boolean)? = if (ids == null || names == null) null else { id, display ->
            id.lowercase() in ids || Guide.normalize(id) in names || display.any { Guide.normalize(it) in names }
        }
        val parser = XmltvParser(fromMs = now - guidePastMs, untilMs = now + guideAheadMs, keepChannel = keep) { programmes += it }
        http.prepareGet(url).execute { res ->
            if (!res.status.isSuccess()) throw IllegalStateException("the provider's guide answered ${res.status.value}")
            val body = res.bodyAsChannel()
            while (true) {
                val line = body.readUTF8Line() ?: break
                parser.feed(line)
                parser.feed("\n")
            }
        }
        parser.finish()
        // An empty answer (guide switched off, wrong link) keeps the last good guide instead.
        if (programmes.isEmpty() && !channels.isNullOrEmpty()) throw IllegalStateException("the provider's guide had no listings for your channels")
        return Guide(programmes, parser.channelNames)
    }

    fun nowNext(channel: Channel): NowNext = state.value.guide.nowNext(channel, clock())

    /**
     * Lets go of the guide in memory (the saved copy stays). For an app that copies the listings
     * into its own shapes (avonioTV Home): one copy instead of two on a TV box with little memory.
     */
    fun releaseGuide() { _state.value = _state.value.copy(guide = Guide(emptyList())) }

    fun catchupUrl(channel: Channel, programme: Programme, serverOffsetMinutes: Int = 0): String? {
        val lineup = state.value.lineup ?: return null
        val src = (lineup.sources.firstOrNull { it.id == channel.sourceId } ?: lineup.source) as? Source.Xtream ?: return null
        val id = channel.xtreamStreamId ?: return null
        if (channel.catchupDays <= 0 || programme.startMs < clock() - channel.catchupDays * DAY) return null
        return xtream.catchupUrl(src, id, programme.startMs, programme.durationMinutes.toInt().coerceAtLeast(1), serverOffsetMinutes)
    }

    companion object { const val DAY = 86_400_000L; const val HOUR = 3_600_000L; const val GUIDE_FRESH_MS = 6 * HOUR }
}
