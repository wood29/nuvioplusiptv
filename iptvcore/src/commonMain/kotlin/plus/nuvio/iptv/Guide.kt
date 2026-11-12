/*
 * Nuvio Plus IPTV core: written from scratch (see iptvcore/LICENSE).
 */
package plus.nuvio.iptv

/**
 * The TV guide: listings per guide channel, matched to the lineup's channels by guide id first,
 * then by name ("ORF 2 HD" finds "ORF2").
 */
class Guide(
    programmes: Collection<Programme>,
    private val channelNames: Map<String, List<String>> = emptyMap()
) {
    private val byGuideId: Map<String, List<Programme>> =
        programmes.groupBy { it.guideId.lowercase() }.mapValues { (_, l) -> l.sortedBy { it.startMs }.dedupe() }

    private val byName: Map<String, String> = buildMap {
        channelNames.forEach { (id, names) -> names.forEach { n -> putIfAbsent(normalize(n), id.lowercase()) } }
        byGuideId.keys.forEach { putIfAbsent(normalize(it), it) }
    }

    val isEmpty get() = byGuideId.isEmpty()

    /** All listings for a channel, oldest first. */
    fun listings(channel: Channel): List<Programme> {
        channel.guideId?.lowercase()?.let { id -> byGuideId[id]?.let { return it } }
        val key = byName[normalize(channel.name)] ?: channel.guideId?.let { byName[normalize(it)] }
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
        /** "ORF 2 HD", "orf2.at", "AT: ORF2 (FHD)" → "orf2". */
        fun normalize(name: String): String {
            var s = name.lowercase()
            s = s.substringAfter(": ", s).substringAfter("| ", s)
            s = s.replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
            s = s.replace(Regex("\\.(at|de|ch|uk|us|tv)$"), "")
            s = s.replace(Regex("\\b(uhd|fhd|hd|sd|4k|hevc|h265|backup|raw)\\b"), " ")
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
