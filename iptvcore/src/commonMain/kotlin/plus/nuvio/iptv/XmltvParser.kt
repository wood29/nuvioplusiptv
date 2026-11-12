/*
 * Nuvio Plus IPTV core: written from scratch (see iptvcore/LICENSE).
 */
package plus.nuvio.iptv

/**
 * A small XMLTV reader (no XML library needed, so it runs on iPhone, iPad and Apple TV alike).
 * It reads <channel> names and every <programme> with its title, sub-title, description,
 * category, episode number, picture, year and cast.
 *
 * Feed it the text in chunks with [feed] (a guide can be tens of MB), then call [finish].
 */
class XmltvParser(
    /** Only keep listings that end after this time (skip the past). */
    private val fromMs: Long = Long.MIN_VALUE,
    /** …and start before this one. */
    private val untilMs: Long = Long.MAX_VALUE,
    private val onProgramme: (Programme) -> Unit
) {
    /** Guide channel id → its display names (for matching channels by name). */
    val channelNames = LinkedHashMap<String, MutableList<String>>()

    private val buffer = StringBuilder()

    fun feed(chunk: CharSequence) {
        buffer.append(chunk)
        drain(final = false)
    }

    fun finish() = drain(final = true)

    private fun drain(final: Boolean) {
        var pos = 0
        while (true) {
            val p = buffer.indexOf("<programme", pos)
            val c = buffer.indexOf("<channel", pos)
            val start = when {
                p < 0 -> c
                c < 0 -> p
                else -> minOf(p, c)
            }
            if (start < 0) break
            val isProgramme = start == p
            val closeTag = if (isProgramme) "</programme>" else "</channel>"
            val end = buffer.indexOf(closeTag, start)
            if (end < 0) {
                // A self-closing <channel …/>?
                if (!isProgramme) {
                    val gt = buffer.indexOf(">", start)
                    if (gt > 0 && buffer[gt - 1] == '/') { pos = gt + 1; continue }
                }
                pos = start
                break
            }
            val element = buffer.substring(start, end + closeTag.length)
            if (isProgramme) readProgramme(element) else readChannel(element)
            pos = end + closeTag.length
        }
        if (final) buffer.setLength(0) else buffer.deleteRange(0, pos)
    }

    private fun readChannel(xml: String) {
        val id = attr(openTag(xml), "id") ?: return
        val names = channelNames.getOrPut(id) { ArrayList() }
        allTexts(xml, "display-name").forEach { if (it.isNotBlank() && it !in names) names += it }
    }

    private fun readProgramme(xml: String) {
        val open = openTag(xml)
        val channel = attr(open, "channel") ?: return
        val start = attr(open, "start")?.let(::parseTime) ?: return
        val stop = attr(open, "stop")?.let(::parseTime) ?: (start + 30 * 60_000L)
        if (stop <= fromMs || start >= untilMs) return
        val title = firstText(xml, "title") ?: return
        val sub = firstText(xml, "sub-title")
        val episodeNum = episode(xml)
        val episode = listOfNotNull(episodeNum, sub).joinToString(" · ").ifBlank { null }
        val iconTag = Regex("<icon[^>]*>").find(xml)?.value
        val credits = Regex("<(actor|director|presenter)[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { decode(it.groupValues[2]).trim() }.filter { it.isNotEmpty() }.distinct().toList()
        onProgramme(
            Programme(
                guideId = channel,
                startMs = start,
                stopMs = stop,
                title = title,
                description = firstText(xml, "desc"),
                category = firstText(xml, "category"),
                episode = episode,
                icon = iconTag?.let { attr(it, "src") },
                year = firstText(xml, "date")?.take(4)?.toIntOrNull(),
                people = credits
            )
        )
    }

    /** "S2 E5" from xmltv_ns ("1.4.0/1" = season 2, episode 5) or the on-screen form. */
    private fun episode(xml: String): String? {
        val matches = Regex("<episode-num([^>]*)>(.*?)</episode-num>", RegexOption.DOT_MATCHES_ALL).findAll(xml).toList()
        matches.firstOrNull { attr(it.groupValues[1], "system") == "xmltv_ns" }?.let { m ->
            val parts = decode(m.groupValues[2]).split('.')
            val s = parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
            val e = parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
            val text = listOfNotNull(s?.let { "S$it" }, e?.let { "E$it" }).joinToString(" ")
            if (text.isNotEmpty()) return text
        }
        return matches.firstOrNull { attr(it.groupValues[1], "system") == "onscreen" }?.let { decode(it.groupValues[2]).trim() }
            ?.ifBlank { null }
    }

    companion object {
        /** "20261112201500 +0100" → epoch ms (no offset = UTC). */
        fun parseTime(s: String): Long? {
            val t = s.trim()
            if (t.length < 12) return null
            val y = t.substring(0, 4).toIntOrNull() ?: return null
            val mo = t.substring(4, 6).toIntOrNull() ?: return null
            val d = t.substring(6, 8).toIntOrNull() ?: return null
            val h = t.substring(8, 10).toIntOrNull() ?: return null
            val mi = t.substring(10, 12).toIntOrNull() ?: return null
            val sec = if (t.length >= 14) t.substring(12, 14).toIntOrNull() ?: 0 else 0
            var offsetMin = 0
            val rest = t.substring(minOf(14, t.length)).trim()
            if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                val oh = rest.substring(1, 3).toIntOrNull() ?: 0
                val om = rest.substring(3, 5).toIntOrNull() ?: 0
                offsetMin = (oh * 60 + om) * if (rest[0] == '-') -1 else 1
            }
            val days = daysFromCivil(y, mo, d)
            return ((days * 86_400L + h * 3_600L + mi * 60L + sec) - offsetMin * 60L) * 1000L
        }

        /** Days since 1970-01-01 (Howard Hinnant's algorithm). */
        private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097L + doe - 719_468L
        }

        internal fun openTag(xml: String) = xml.substring(0, xml.indexOf('>').let { if (it < 0) xml.length else it + 1 })

        internal fun attr(tag: String, name: String): String? {
            val m = Regex("""\s$name\s*=\s*("([^"]*)"|'([^']*)')""").find(tag) ?: return null
            return decode(m.groupValues[2].ifEmpty { m.groupValues[3] })
        }

        internal fun firstText(xml: String, tag: String): String? =
            Regex("<$tag(\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)
                ?.let { decode(it.groupValues[2]).trim() }?.ifBlank { null }

        internal fun allTexts(xml: String, tag: String): List<String> =
            Regex("<$tag(\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
                .map { decode(it.groupValues[2]).trim() }.toList()

        internal fun decode(s: String): String {
            var t = s
            if (t.startsWith("<![CDATA[")) t = t.removePrefix("<![CDATA[").removeSuffix("]]>")
            if ('&' !in t) return t
            return Regex("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos);").replace(t) { m ->
                when (val e = m.groupValues[1]) {
                    "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                    else -> {
                        val code = if (e.startsWith("#x") || e.startsWith("#X")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
                        code?.let { c -> if (c in 0..0xFFFF) c.toChar().toString() else m.value } ?: m.value
                    }
                }
            }
        }
    }
}
