/*
 * Nuvio Plus IPTV core: written from scratch (see iptvcore/LICENSE).
 */
package plus.nuvio.iptv

/**
 * Reads an M3U / M3U8 playlist:
 *
 *     #EXTM3U url-tvg="http://…/guide.xml"
 *     #EXTINF:-1 tvg-id="orf1.at" tvg-name="ORF 1" tvg-logo="http://…" group-title="Austria",ORF 1 HD
 *     #EXTVLCOPT:http-user-agent=Foo
 *     http://…/stream.ts
 */
object M3uParser {

    fun parse(text: String, source: Source.M3u): Lineup {
        val groups = LinkedHashMap<String, Group>()
        val channels = ArrayList<Channel>()
        var guideUrl: String? = null
        var pendingInfo: String? = null
        val pendingHeaders = LinkedHashMap<String, String>()
        var pendingGroup: String? = null
        val seenIds = HashMap<String, Int>()

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = attributes(line)
                    guideUrl = attrs["url-tvg"] ?: attrs["x-tvg-url"]
                    guideUrl = guideUrl?.split(',')?.firstOrNull()?.trim()?.ifBlank { null }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = line
                    pendingHeaders.clear()
                    pendingGroup = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> pendingHeaders["User-Agent"] = value
                        "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                        "http-origin" -> pendingHeaders["Origin"] = value
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val info = pendingInfo ?: "#EXTINF:-1,$line"
                    val attrs = attributes(info)
                    val title = displayName(info).ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { line }
                    val groupName = (attrs["group-title"] ?: pendingGroup)?.trim()?.ifBlank { null } ?: "Other"
                    val group = groups.getOrPut(groupName.lowercase()) { Group(groupName.lowercase(), groupName) }
                    val baseId = attrs["tvg-id"]?.ifBlank { null } ?: title
                    // Two channels with the same tvg-id (HD and SD, say) still need their own id.
                    val n = (seenIds[baseId] ?: 0) + 1
                    seenIds[baseId] = n
                    val headers = LinkedHashMap(pendingHeaders)
                    source.userAgent?.let { if ("User-Agent" !in headers) headers["User-Agent"] = it }
                    channels += Channel(
                        id = if (n == 1) baseId else "$baseId#$n",
                        sourceId = source.id,
                        name = title,
                        number = attrs["tvg-chno"]?.toIntOrNull() ?: (channels.size + 1),
                        groupId = group.id,
                        logo = attrs["tvg-logo"]?.ifBlank { null },
                        guideId = attrs["tvg-id"]?.ifBlank { null },
                        streamUrl = line,
                        catchupDays = (attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"])?.toIntOrNull() ?: 0,
                        headers = headers
                    )
                    pendingInfo = null
                    pendingHeaders.clear()
                    pendingGroup = null
                }
            }
        }
        return Lineup(source, groups.values.toList(), channels, guideUrl)
    }

    /** The name after the last comma that isn't inside quotes. */
    internal fun displayName(info: String): String {
        var inQuotes = false
        var lastComma = -1
        info.forEachIndexed { i, c ->
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) lastComma = i
        }
        return if (lastComma >= 0) info.substring(lastComma + 1).trim() else ""
    }

    /** key="value" pairs (also key=value without quotes). Keys are lower-case. */
    internal fun attributes(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        val s = line
        while (i < s.length) {
            // A key: letters, digits, - and _
            if (s[i].isLetter()) {
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == '_')) i++
                if (i < s.length && s[i] == '=') {
                    val key = s.substring(start, i).lowercase()
                    i++
                    val value: String
                    if (i < s.length && s[i] == '"') {
                        val end = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }
                        value = s.substring(i + 1, end)
                        i = end + 1
                    } else {
                        val start2 = i
                        while (i < s.length && !s[i].isWhitespace() && s[i] != ',') i++
                        value = s.substring(start2, i)
                    }
                    out[key] = value
                    continue
                }
            } else if (s[i] == ',' ) {
                // The display name starts here: stop, unless more quoted attributes follow later.
                if (!s.substring(i).contains("=\"")) break
            }
            i++
        }
        return out
    }
}
