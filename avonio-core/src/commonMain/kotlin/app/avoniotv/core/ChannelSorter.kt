/*
 * avonioTV core: written from scratch for avonioTV (see core/LICENSE).
 */
package app.avoniotv.core

/**
 * "Sort your channels": you say what you watch (sport, news, kids…) and which countries, and the
 * channel list gets new groups on top ("Sport · Austria", "News · Austria"…), each channel once,
 * the best copy kept (FHD before HD before SD). Everything else stays below, exactly as the
 * provider sent it. Nothing is deleted, and it all runs on the device: no channel names leave it.
 */
object ChannelSorter {

    enum class Kind(val label: String, words: String) {
        SPORT("Sport", "sport|sports|sportv|deporte|deportes|esport|calcio|futbol|fútbol|football|soccer|f1|formula|moto ?gp|tennis|golf|nba|nfl|nhl|mlb|ufc|wwe|boxing|fight|racing|motor|rugby|cricket|darts|cycling|eurosport|dazn|sky sport|bein|espn|arena sport|sport ?klub|laola|setanta|premier league|bundesliga|la ?liga|serie a|ligue 1|champions"),
        NEWS("News", "news|nachrichten|info|noticias|notizie|journal|cnn|bbc world|euronews|al jazeera|bloomberg|cnbc|sky news|n-?tv|welt|tagesschau|phoenix|oe24|puls ?24|zdfinfo|rtl ?news|france ?24|dw|rt |tg ?24|business|weather|wetter"),
        KIDS("Kids", "kids|kinder|junior|child|enfant|niños|cartoon|disney|nick|nickelodeon|toon|baby|boomerang|kika|super ?rtl|toggo|minimax|duck ?tv|jim ?jam|pbs kids|cbeebies|cbbc"),
        MOVIES("Movies", "movie|movies|film|films|filme|cinema|kino|cine|hbo|cinemax|sky cinema|showtime|starz|tcm|amc|paramount|mgm|action|thriller|comedy central|fox ?movies|tele ?5|sky ?(premiere|hits|action|family)"),
        SERIES("Series", "series|serien|séries|serie|tv ?shows|sitcom|crime|syfy|sky ?atlantic|fox ?(crime|life)?$|universal ?tv|13th street|sony ?channel|warner ?tv|axn|showcase"),
        MUSIC("Music", "music|musik|música|mtv|vh1|radio|deluxe music|melodie|clubbing|hits|concert|vevo|trace|stingray"),
        DOCS("Documentaries", "doku|docu|documentary|documentaries|documental|discovery|nat ?geo|national geographic|history|animal planet|planete|planet|science|nature|wild|travel|terra ?x|arte|spiegel ?(geschichte|tv)|explorer|odisea|viasat (explore|history|nature)|curiosity|bbc earth|love nature"),
        /** Everything else from your countries: the main channels (ORF 1, ZDF, BBC One…). */
        GENERAL("TV channels", "");

        internal val rx: Regex? = if (words.isEmpty()) null else Regex("(?i)(?<![\\p{L}\\d])($words)(?![\\p{L}\\d])")
    }

    /**
     * Your answers: kinds in the order you want them, then countries in order. [only]: show only
     * what you picked (the rest of the channels, and Movies & Series categories of other countries
     * or for children when Kids isn't picked, go out of the lists); off: the rest stays below.
     */
    data class Plan(val kinds: List<Kind>, val countries: List<String>, val only: Boolean = true) {
        fun encode() = kinds.joinToString(",") { it.name } + ";" + countries.joinToString(",") + ";" + (if (only) "only" else "all")
        companion object {
            fun decode(s: String?): Plan? {
                if (s.isNullOrBlank() || ';' !in s) return null
                val parts = s.split(';')
                val k = parts[0].split(',').mapNotNull { n -> Kind.values().firstOrNull { it.name == n } }
                val c = parts.getOrElse(1) { "" }.split(',').filter { it.isNotBlank() }
                return if (k.isEmpty()) null else Plan(k, c, parts.getOrNull(2) != "all")
            }
        }
    }

    /** Codes that aren't a country or language: never a reason to hide a category. */
    private val NOT_A_PLACE = setOf("VIP", "PPV", "4K", "ADULT", "MULTI", "EN")

    /**
     * Movies & Series: true when [plan] shows only what you picked and this category is for
     * children (Kids not picked) or of a country / language you didn't pick.
     */
    fun hidesCategory(plan: Plan?, name: String): Boolean {
        if (plan == null || !plan.only) return false
        if (Kind.KIDS !in plan.kinds && Kind.KIDS.rx?.containsMatchIn(name) == true) return true
        if (plan.countries.isEmpty()) return false
        val code = Regions.of(name) ?: return false
        if (code in NOT_A_PLACE) return false
        return code !in plan.countries
    }

    /** One new group on top. */
    data class Section(val kind: Kind, val country: String?, val channels: List<Channel>) {
        val title get() = kind.label + (country?.let { " · " + countryName(it) } ?: "")
    }

    data class Result(val sections: List<Section>, val doublesRemoved: Int, val restCount: Int, val lineup: Lineup) {
        val onTop get() = sections.sumOf { it.channels.size }
    }

    const val GROUP_PREFIX = "sorted:"

    /** The countries in this lineup, biggest first, with how many channels each has. */
    fun countries(lineup: Lineup, skipGroups: Set<String> = emptySet()): List<Pair<String, Int>> {
        val groups = lineup.groups.associateBy { it.id }
        return lineup.channels.filter { it.groupId !in skipGroups }.mapNotNull { countryOf(it, groups[it.groupId])?.takeIf { c -> c in NAMES || c in LANGUAGES } }
            .groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** How many channels of each kind (in the chosen countries, or all). */
    fun kindCounts(lineup: Lineup, countries: Collection<String> = emptyList(), skipGroups: Set<String> = emptySet()): Map<Kind, Int> {
        val groups = lineup.groups.associateBy { it.id }
        val out = HashMap<Kind, Int>()
        for (c in lineup.channels) {
            if (c.groupId in skipGroups) continue
            val g = groups[c.groupId]
            if (countries.isNotEmpty() && countryOf(c, g) !in countries) continue
            kindOrGeneral(c, g)?.let { out[it] = (out[it] ?: 0) + 1 }
        }
        return out
    }

    /** What the list would look like with [plan]; [Result.lineup] is the sorted lineup itself. */
    fun sort(lineup: Lineup, plan: Plan, skipGroups: Set<String> = emptySet()): Result {
        val groups = lineup.groups.associateBy { it.id }
        val countries = plan.countries
        val bySection = LinkedHashMap<Pair<Kind, String?>, MutableList<Channel>>()
        // Sections in your order: kind first, then country.
        for (k in plan.kinds) {
            if (countries.isEmpty()) bySection[k to null] = ArrayList()
            else countries.forEach { bySection[k to it] = ArrayList() }
        }
        for (c in lineup.channels) {
            // Groups you've hidden stay hidden: their channels aren't moved up.
            if (c.groupId in skipGroups) continue
            val g = groups[c.groupId]
            val kind = kindOrGeneral(c, g) ?: continue
            if (kind !in plan.kinds) continue
            val country = if (countries.isEmpty()) null else (countryOf(c, g)?.takeIf { it in countries } ?: continue)
            bySection[kind to country]?.add(c)
        }
        var doubles = 0
        val moved = HashSet<String>()
        val sections = bySection.mapNotNull { (key, list) ->
            // The same channel several times (HD, FHD, SD, backup): keep the best one.
            val best = list.groupBy { sameKey(it) }.values.map { copies ->
                doubles += copies.size - 1
                copies.minWith(compareBy<Channel> { qualityRank(it.name) }.thenBy { it.number })
            }.sortedBy { it.number }
            best.forEach { moved += it.id }
            if (best.isEmpty()) null else Section(key.first, key.second, best)
        }
        val newGroups = sections.map { s -> Group(GROUP_PREFIX + s.kind.name + ":" + (s.country ?: "all"), s.title) }
        val sectionOf = HashMap<String, String>()
        sections.forEachIndexed { i, s -> s.channels.forEach { sectionOf[it.id] = newGroups[i].id } }
        // The chosen channels move up into their new group; everything else stays where it was.
        val channels = sections.flatMapIndexed { i, s -> s.channels.map { it.copy(groupId = newGroups[i].id) } } +
            lineup.channels.filter { it.id !in moved }
        val sorted = lineup.copy(groups = newGroups + lineup.groups, channels = channels)
        return Result(sections, doubles, lineup.channels.size - moved.size, sorted)
    }

    /** The sorted lineup, or [lineup] as it was when there's no plan (or nothing matched). */
    fun apply(lineup: Lineup, plan: Plan?, skipGroups: Set<String> = emptySet()): Lineup {
        if (plan == null) return lineup
        if (plan.only) return runCatching { pickOnly(lineup, plan, skipGroups) }.getOrNull() ?: lineup
        val r = runCatching { sort(lineup, plan, skipGroups) }.getOrNull() ?: return lineup
        return if (r.sections.isEmpty()) lineup else r.lineup
    }

    /**
     * "Show only what I picked": your provider's own groups stay (so a country opens into its
     * Sports, Movies, Kids… as the provider made them), holding only the channels of the kinds and
     * countries you picked, the best copy of each. Groups come in your order: country, then kind.
     * A group whose name doesn't say its country gets it in front ("Sky Sports" → "UK | Sky Sports"),
     * so it lands in the right folder. The other channels stay in the lineup without a group
     * (favourites, search and the last channel still find them).
     */
    fun pickOnly(lineup: Lineup, plan: Plan, skipGroups: Set<String> = emptySet()): Lineup? {
        val groups = lineup.groups.associateBy { it.id }
        val kindRank = plan.kinds.withIndex().associate { it.value to it.index }
        val countryRank = plan.countries.withIndex().associate { it.value to it.index }
        val kindOfCh = HashMap<String, Kind>(); val countryOfCh = HashMap<String, String?>()
        val kept = lineup.channels.filter { c ->
            if (c.groupId in skipGroups) return@filter false
            val g = groups[c.groupId]
            val k = kindOrGeneral(c, g) ?: return@filter false
            if (k !in kindRank) return@filter false
            val cc = countryOf(c, g)
            if (countryRank.isNotEmpty() && cc !in countryRank) return@filter false
            kindOfCh[c.id] = k; countryOfCh[c.id] = cc
            true
        }
        if (kept.isEmpty()) return null
        val byGroup = kept.groupBy { it.groupId }
        val order = lineup.groups.withIndex().associate { it.value.id to it.index }
        val shown = byGroup.keys.mapNotNull { groups[it] }.map { g ->
            val chans = byGroup.getValue(g.id)
            val cc = chans.mapNotNull { countryOfCh[it.id] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val k = chans.mapNotNull { kindOfCh[it.id] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val named = if (cc != null && Regions.of(g.name) == null) g.copy(name = "$cc | ${g.name.trim()}") else g
            Triple(named, (cc?.let { countryRank[it] } ?: Int.MAX_VALUE), (k?.let { kindRank[it] } ?: Int.MAX_VALUE))
        }.sortedWith(compareBy<Triple<Group, Int, Int>> { it.second }.thenBy { it.third }.thenBy { order[it.first.id] ?: 0 }).map { it.first }
        val best = byGroup.values.flatMap { list -> list.groupBy { sameKey(it) }.values.map { copies -> copies.minWith(compareBy<Channel> { qualityRank(it.name) }.thenBy { it.number }) } }
        val bestIds = best.map { it.id }.toHashSet()
        val rest = lineup.channels.filter { it.id !in bestIds }.map { it.copy(groupId = "hidden:" + it.groupId) }
        return lineup.copy(groups = shown, channels = best.sortedBy { it.number } + rest)
    }

    // ── How a channel is read ────────────────────────────────────────────────────────────

    fun kindOf(c: Channel, g: Group?): Kind? {
        val name = cleanName(c.name)
        // The channel's own name decides first ("Sky Sport News" is sport), then its group's.
        Kind.values().firstOrNull { it.rx?.containsMatchIn(name) == true }?.let { return it }
        val gn = g?.name?.let { stripTag(it) } ?: return null
        return Kind.values().firstOrNull { it.rx?.containsMatchIn(gn) == true }
    }

    private val adultRx = Regex("(?i)adult|xxx|18\\+|porn|erotic")

    /** An adult channel (by its own name or its group's): the apps never remember these. */
    fun isAdult(c: Channel, g: Group?): Boolean =
        adultRx.containsMatchIn(c.name) || g?.name?.let { adultRx.containsMatchIn(it) || Regions.of(it) == "ADULT" } == true

    /** Its kind, or "TV channels" for an ordinary channel (never an adult one). */
    fun kindOrGeneral(c: Channel, g: Group?): Kind? {
        if (adultRx.containsMatchIn(c.name) || g?.name?.let { adultRx.containsMatchIn(it) } == true) return null
        return kindOf(c, g) ?: Kind.GENERAL
    }

    /** "AT| ORF 1", group "EU | AUSTRIA" or "DE | SPORT" → the country code. */
    fun countryOf(c: Channel, g: Group?): String? {
        tagCountry(c.name)?.let { return it }
        g?.name?.let { tagCountry(it) }?.let { return it }
        // Any other naming style: "Sport DE", "Movies (UK)", "Germany Sports", "Arabic", "RTL Austria".
        return (g?.name?.let { Regions.of(it) } ?: Regions.of(c.name))?.takeIf { it in NAMES || it in LANGUAGES }
    }

    /** Languages and regions that work like a country when you pick what goes on top. */
    private val LANGUAGES = setOf("AR", "EXYU", "LATAM", "KU", "AFR", "CARIB")

    private fun tagCountry(name: String): String? {
        val t = ChannelTags.of(name) ?: return null
        val code = if (" | " in t) t.substringAfter(" | ") else t
        val c = COUNTRY_WORDS[code] ?: code
        return c.takeIf { it.length in 2..4 && it !in NOT_COUNTRIES }
    }

    fun countryName(code: String): String = NAMES[code] ?: Regions.name(code)

    private val qualityRx = Regex("(?i)(?<![\\p{L}\\d])(uhd|4k|fhd|full ?hd|hd|sd|hevc|h\\.?265|raw|backup|alt|\\d{3,4}p|ᴴᴰ|ᶠᴴᴰ|ᵁᴴᴰ)(?![\\p{L}\\d])|[\\[(].*?[\\])]")

    private fun cleanName(n: String) = stripTag(n)
    private fun stripTag(n: String) = if (n.indexOf('|') in 1..12) n.substringAfter('|').trim() else n

    /** The same channel whatever its quality: "AT| ORF 1 FHD" and "AT| ORF 1 HD" → "orf 1". */
    private fun sameKey(c: Channel): String = Guide.normalize(stripTag(c.name).replace(qualityRx, " ")).ifEmpty { c.name.lowercase() }

    private fun qualityRank(name: String): Int {
        val n = name.lowercase()
        return when {
            Regex("backup|alt(?![a-z])|raw").containsMatchIn(n) -> 6
            Regex("uhd|4k|ᵁᴴᴰ").containsMatchIn(n) -> 1  // very heavy streams: after FHD
            Regex("fhd|full ?hd|1080|ᶠᴴᴰ").containsMatchIn(n) -> 0
            Regex("(?<![a-z])hd(?![a-z])|720|ᴴᴰ").containsMatchIn(n) -> 2
            Regex("(?<![a-z])sd(?![a-z])|576|480").containsMatchIn(n) -> 4
            else -> 3
        }
    }

    private val NOT_COUNTRIES = setOf("VIP", "4K", "ADULT", "MULTI", "MULTI-LANG", "OTHER", "PPV", "EVENTS", "VOD", "INT", "EU", "AS", "AM", "NA", "SA", "LATAM", "AF", "EN", "AR", "KU", "EXYU", "EX-YU")

    /** Countries written out in region groups ("EU | AUSTRIA"). */
    private val COUNTRY_WORDS = mapOf(
        "AUSTRIA" to "AT", "GERMANY" to "DE", "DEUTSCHLAND" to "DE", "SWITZERLAND" to "CH", "SCHWEIZ" to "CH", "UK" to "UK", "ENGLAND" to "UK",
        "FRANCE" to "FR", "ITALY" to "IT", "ITALIA" to "IT", "SPAIN" to "ES", "ESPANA" to "ES", "PORTUGAL" to "PT", "NETHERLANDS" to "NL",
        "HOLLAND" to "NL", "BELGIUM" to "BE", "POLAND" to "PL", "ROMANIA" to "RO", "HUNGARY" to "HU", "CZECH" to "CZ", "SLOVAKIA" to "SK",
        "GREECE" to "GR", "TURKEY" to "TR", "SWEDEN" to "SE", "NORWAY" to "NO", "DENMARK" to "DK", "FINLAND" to "FI", "IRELAND" to "IE",
        "USA" to "US", "CANADA" to "CA", "AUSTRALIA" to "AU", "BULGARIA" to "BG", "CROATIA" to "HR", "SERBIA" to "RS", "ALBANIA" to "AL",
        "SLOVENIA" to "SI", "BOSNIA" to "BA", "RUSSIA" to "RU", "UKRAINE" to "UA", "INDIA" to "IN", "PAKISTAN" to "PK", "BRAZIL" to "BR",
        "MEXICO" to "MX", "ARGENTINA" to "AR-C", "DE" to "DE", "GB" to "UK"
    )

    private val NAMES = mapOf(
        "AT" to "Austria", "DE" to "Germany", "CH" to "Switzerland", "UK" to "United Kingdom", "US" to "United States", "CA" to "Canada",
        "FR" to "France", "IT" to "Italy", "ES" to "Spain", "PT" to "Portugal", "NL" to "Netherlands", "BE" to "Belgium", "PL" to "Poland",
        "RO" to "Romania", "HU" to "Hungary", "CZ" to "Czechia", "SK" to "Slovakia", "GR" to "Greece", "TR" to "Turkey", "SE" to "Sweden",
        "NO" to "Norway", "DK" to "Denmark", "FI" to "Finland", "IE" to "Ireland", "AU" to "Australia", "BG" to "Bulgaria", "HR" to "Croatia",
        "RS" to "Serbia", "AL" to "Albania", "SI" to "Slovenia", "BA" to "Bosnia", "RU" to "Russia", "UA" to "Ukraine", "IN" to "India",
        "PK" to "Pakistan", "BR" to "Brazil", "MX" to "Mexico", "AR-C" to "Argentina", "NZ" to "New Zealand", "ZA" to "South Africa",
        "IL" to "Israel", "IR" to "Iran", "JP" to "Japan", "KR" to "Korea", "CN" to "China", "PH" to "Philippines", "AFG" to "Afghanistan",
        "BD" to "Bangladesh", "LU" to "Luxembourg", "LT" to "Lithuania", "LV" to "Latvia", "EE" to "Estonia", "MK" to "North Macedonia",
        "ME" to "Montenegro", "CY" to "Cyprus", "MT" to "Malta", "IS" to "Iceland"
    )
}
