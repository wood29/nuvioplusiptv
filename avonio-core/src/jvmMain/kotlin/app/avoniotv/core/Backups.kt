/*
 * avonioTV core.
 * Written from scratch; not derived from Nuvio or any GPL code.
 * See core/LICENSE.
 */
package app.avoniotv.core

/**
 * Channel fail-over: providers often carry the same channel several times ("ORF 2", "ORF 2 HD",
 * "AT: ORF 2 FHD"). When one copy fails, another copy can take over.
 */
object Backups {
    private val quality = Regex(
        "\\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|h\\.265|h264|50fps|60fps|25fps|raw|backup|alt|multi|vip|hq|lq|plus)\\b"
    )
    private val prefix = Regex("^\\s*(\\|[a-z]{2,3}\\||[a-z]{2,3}\\s*[:|\\-]+)\\s*")
    private val brackets = Regex("[\\[(][^\\])]*[\\])]")
    private val other = Regex("[^a-z0-9]")

    /** The channel's name without country tags, quality labels and punctuation. */
    fun key(name: String): String {
        var s = name.lowercase()
            .replace('ᴴ', 'h').replace('ᴰ', 'd')
        s = brackets.replace(s, " ")
        s = prefix.replace(s, "")
        s = quality.replace(s, " ")
        return other.replace(s, "")
    }

    /** Other copies of [channel] in [all], best first (same group, then the rest). */
    fun forChannel(channel: Channel, all: List<Channel>): List<Channel> {
        val k = key(channel.name)
        if (k.length < 2) return emptyList()
        return all.asSequence()
            .filter { it.id != channel.id && key(it.name) == k }
            .sortedBy { if (it.groupId == channel.groupId) 0 else 1 }
            .take(4).toList()
    }
}
