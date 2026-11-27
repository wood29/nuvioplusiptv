/*
 * avonioTV core: written from scratch for avonioTV (see core/LICENSE).
 */
package app.avoniotv.core

/**
 * The TV guide: listings per guide channel, matched to the lineup's channels by guide id first,
 * then by name ("ORF 2 HD" finds "ORF2").
 */
class Guide(
    programmes: Collection<Programme>,
    val channelNames: Map<String, List<String>> = emptyMap()
) {
    private val byGuideId: Map<String, List<Programme>> =
        programmes.groupBy { it.guideId.lowercase() }.mapValues { (_, l) -> l.sortedBy { it.startMs }.dedupe() }

    /** Name → every guide channel with that name ("tv1" is TV1 in Austria and TV1 in Bulgaria). */
    private val byName: Map<String, List<String>> = HashMap<String, MutableList<String>>().apply {
        channelNames.forEach { (id, names) -> names.forEach { n -> getOrPut(normalize(n)) { ArrayList(2) }.let { l -> id.lowercase().let { i -> if (i !in l) l += i } } } }
        byGuideId.keys.forEach { id -> getOrPut(normalize(id)) { ArrayList(2) }.let { l -> if (id !in l) l += id } }
    }

    /** Each guide channel's country from its id ("tv1.bg" → "bg"), worked out once. */
    private val idCountryOf: Map<String, String> = buildMap {
        byName.values.forEach { l -> l.forEach { id -> idCountryCode(id)?.let { put(id, it) } } }
    }

    /** The guide channel for this name, from the channel's own country when names clash. */
    private fun pick(key: String, channel: Channel): String? {
        val ids = byName[key] ?: return null
        // The usual case: one guide channel, no country in its id. Nothing to check.
        if (ids.size == 1 && ids[0] !in idCountryOf) return ids[0]
        val mine = countryOf(channel)
        if (mine == null) return ids.firstOrNull { it !in idCountryOf } ?: ids[0]
        ids.firstOrNull { idCountryOf[it] == mine }?.let { return it }
        // No guide channel from this country: one without a country, else none (a foreign one shows wrong listings).
        return ids.firstOrNull { it !in idCountryOf }
    }

    val isEmpty get() = byGuideId.isEmpty()
    /** Guide channels with listings (for "Guide: 312 channels"). */
    val channelCount get() = byGuideId.size

    /** Every listing (for saving the guide on the device). */
    fun all(): Sequence<Programme> = byGuideId.values.asSequence().flatten()

    internal fun debug() = "ids=${byGuideId.mapValues { it.value.size }} names=$byName"

    /** All listings for a channel, oldest first. */
    fun listings(channel: Channel): List<Programme> {
        channel.guideId?.lowercase()?.let { id -> byGuideId[id]?.let { return it } }
        val key = pick(normalize(channel.name), channel) ?: channel.guideId?.let { pick(normalize(it), channel) }
        return key?.let { byGuideId[it] }.orEmpty()
    }

    fun nowNext(channel: Channel, nowMs: Long): NowNext {
        val list = listings(channel)
        val i = list.indexOfFirst { nowMs < it.stopMs }
        if (i < 0) return NowNext(null, null, null)
        val first = list[i]
        return if (first.isOnAt(nowMs)) NowNext(first, list.getOrNull(i + 1), list.getOrNull(i + 2))
        else NowNext(null, first, list.getOrNull(i + 1))
    }

    /** Listings between two times (for the time grid). */
    fun between(channel: Channel, fromMs: Long, toMs: Long): List<Programme> =
        listings(channel).filter { it.stopMs > fromMs && it.startMs < toMs }

    /** Other airings of the same title anywhere in the guide, soonest first ("Also shown"). */
    fun alsoShown(title: String, afterMs: Long, lineup: List<Channel>, limit: Int = 4): List<Pair<Channel, Programme>> =
        lineup.asSequence().flatMap { ch ->
            listings(ch).asSequence().filter { it.startMs > afterMs && it.title.equals(title, ignoreCase = true) }.map { ch to it }
        }.sortedBy { it.second.startMs }.take(limit).toList()

    companion object {
        private val idCountry = Regex("\\.([a-z]{2})(?:@.*)?$")

        /** "AT| TV1 HD" → "at" (also "uk" → "gb", as guides write it). */
        internal fun countryOf(channel: Channel): String? =
            ChannelSorter.countryOf(channel, null)?.lowercase()?.let { if (it == "uk") "gb" else it }?.takeIf { it.length == 2 }

        /** "tv1.bg" → "bg"; null when the id says no country. */
        internal fun idCountryCode(guideId: String): String? =
            idCountry.find(guideId.lowercase())?.groupValues?.get(1)?.let { if (it == "uk") "gb" else it }?.takeIf { it !in setOf("tv", "hd", "fm", "sd") }

        /** "ORF 2 HD", "orf2.at", "AT: ORF2 (FHD)" → "orf2". */
        // Made once (they were rebuilt on every call: hundreds of thousands of times for the sports list).
        private val brackets = Regex("\\(.*?\\)|\\[.*?]")
        private val domain = Regex("\\.(at|de|ch|uk|us|tv)$")
        private val quality = Regex("\\b(uhd|fhd|hd|sd|4k|hevc|h265|backup|raw)\\b")

        fun normalize(name: String): String {
            var s = name.lowercase()
            // "AT: ORF 2" / "AT | ORF 2": drop the country prefix.
            s = s.substringAfter(": ", s)
            s = s.substringAfter("| ", s)
            s = s.replace(brackets, " ")
            s = s.replace(domain, "")
            s = s.replace(quality, " ")
            return s.filter { it.isLetterOrDigit() }
        }

        /** Some guides list the same show twice (from two feeds): keep one per start time. */
        private fun List<Programme>.dedupe(): List<Programme> {
            val out = ArrayList<Programme>(size)
            forEach { p -> if (out.lastOrNull()?.startMs != p.startMs) out += p }
            return out
        }
    }
}
