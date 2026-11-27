/*
 * avonioTV core: written from scratch for avonioTV (see core/LICENSE).
 */
package app.avoniotv.core

/**
 * Which country or language a channel group belongs to, for any provider's naming style:
 *   "AT | ORF", "AT: Sport", "[DE] Kino", "EU | GERMANY SPORTS"   (a tag in front)
 *   "Sport DE", "Movies (UK)", "News [FR]"                          (a tag at the end)
 *   "Germany Sports", "Deutschland HD", "Österreich", "UK Entertainment" (the name written out)
 * and the folders built from it. When a list can't be read this way (a small playlist with
 * "News", "Movies", "Kids"), [plan] says so and the apps show the plain list instead.
 */
object Regions {

    /** Folder codes: countries (ISO-like, UK for Britain), languages (AR, EN…) and a few kinds (VIP, PPV, 4K). */
    private val NAMES: Map<String, String> = linkedMapOf(
        "AT" to "Austria", "DE" to "Germany", "CH" to "Switzerland", "UK" to "United Kingdom", "IE" to "Ireland", "US" to "United States",
        "CA" to "Canada", "AU" to "Australia", "NZ" to "New Zealand", "FR" to "France", "BE" to "Belgium", "NL" to "Netherlands",
        "LU" to "Luxembourg", "IT" to "Italy", "ES" to "Spain", "PT" to "Portugal", "PL" to "Poland", "CZ" to "Czechia", "SK" to "Slovakia",
        "HU" to "Hungary", "RO" to "Romania", "BG" to "Bulgaria", "GR" to "Greece", "CY" to "Cyprus", "TR" to "Turkey", "HR" to "Croatia",
        "RS" to "Serbia", "BA" to "Bosnia", "SI" to "Slovenia", "MK" to "North Macedonia", "ME" to "Montenegro", "AL" to "Albania",
        "EXYU" to "Ex-Yugoslavia", "SE" to "Sweden", "NO" to "Norway", "DK" to "Denmark", "FI" to "Finland", "IS" to "Iceland",
        "EE" to "Estonia", "LV" to "Latvia", "LT" to "Lithuania", "RU" to "Russia", "UA" to "Ukraine", "IL" to "Israel", "IR" to "Iran",
        "IN" to "India", "PK" to "Pakistan", "BD" to "Bangladesh", "AFG" to "Afghanistan", "CN" to "China", "JP" to "Japan", "KR" to "Korea",
        "PH" to "Philippines", "TH" to "Thailand", "VN" to "Vietnam", "ID" to "Indonesia", "MY" to "Malaysia", "BR" to "Brazil", "MX" to "Mexico", "AR-C" to "Argentina", "MT" to "Malta",
        "LATAM" to "Latin America", "ZA" to "South Africa", "NG" to "Nigeria", "AFR" to "Africa", "CARIB" to "Caribbean",
        "AR" to "Arabic", "KU" to "Kurdish", "EN" to "English", "MULTI" to "Multi-language",
        "VIP" to "VIP", "PPV" to "Pay-per-view", "4K" to "4K / UHD", "ADULT" to "Adult"
    )

    /** Written-out names (several languages) → code. Matched as whole words, case and accents ignored. */
    private val WORDS: Map<String, String> = buildMap {
        fun w(code: String, vararg names: String) = names.forEach { put(it, code) }
        w("AT", "austria", "osterreich", "oesterreich", "austrian")
        w("DE", "germany", "deutschland", "german", "deutsch", "allemagne", "alemania")
        w("CH", "switzerland", "schweiz", "suisse", "svizzera", "swiss")
        w("UK", "uk", "united kingdom", "great britain", "britain", "british", "england", "english uk", "scotland", "wales")
        w("IE", "ireland", "irish", "eire")
        w("US", "usa", "united states")
        w("CA", "canada", "canadian"); w("AU", "australia", "australian"); w("NZ", "new zealand")
        w("FR", "france", "french", "francais"); w("BE", "belgium", "belgique", "belgie", "belgian")
        w("NL", "netherlands", "nederland", "holland", "dutch"); w("LU", "luxembourg")
        w("IT", "italy", "italia", "italian", "italiano"); w("ES", "spain", "espana", "spanish", "espanol")
        w("PT", "portugal", "portuguese", "portugues"); w("PL", "poland", "polska", "polish")
        w("CZ", "czech", "czechia", "cesko"); w("SK", "slovakia", "slovak", "slovensko")
        w("HU", "hungary", "magyar", "hungarian"); w("RO", "romania", "romanian")
        w("BG", "bulgaria", "bulgarian"); w("GR", "greece", "greek", "hellas"); w("CY", "cyprus")
        w("TR", "turkey", "turkiye", "turkish", "turk"); w("HR", "croatia", "hrvatska", "croatian")
        w("RS", "serbia", "srbija", "serbian"); w("BA", "bosnia"); w("SI", "slovenia", "slovenija")
        w("MK", "macedonia"); w("ME", "montenegro"); w("AL", "albania", "albanian", "shqip")
        w("EXYU", "ex yu", "exyu", "ex-yu", "balkan", "balkans", "yugoslavia")
        w("SE", "sweden", "sverige", "swedish"); w("NO", "norway", "norge", "norwegian")
        w("DK", "denmark", "danmark", "danish"); w("FI", "finland", "suomi", "finnish"); w("IS", "iceland")
        w("EE", "estonia"); w("LV", "latvia"); w("LT", "lithuania")
        w("RU", "russia", "russian"); w("UA", "ukraine", "ukrainian"); w("IL", "israel", "hebrew")
        w("IR", "iran", "persian", "farsi"); w("IN", "india", "indian", "hindi"); w("PK", "pakistan", "urdu")
        w("BD", "bangladesh", "bangla"); w("AFG", "afghanistan", "afghan"); w("CN", "china", "chinese")
        w("JP", "japan", "japanese"); w("KR", "korea", "korean"); w("PH", "philippines", "filipino", "pinoy")
        w("TH", "thailand", "thai"); w("VN", "vietnam", "vietnamese"); w("ID", "indonesia"); w("MY", "malaysia")
        w("BR", "brazil", "brasil", "brazilian"); w("MX", "mexico", "mexicano"); w("AR-C", "argentina"); w("MT", "malta"); w("LATAM", "latino", "latin america", "latam", "hispanic")
        w("ZA", "south africa"); w("NG", "nigeria"); w("AFR", "africa", "african"); w("CARIB", "caribbean")
        w("AR", "arabic", "arab", "arabia", "mbc", "osn"); w("KU", "kurdish", "kurdistan")
        w("EN", "english"); w("MULTI", "multi lang", "multi-lang", "multilang", "multi language")
        w("VIP", "vip"); w("PPV", "ppv", "pay per view"); w("4K", "4k", "uhd")
        w("ADULT", "adult", "xxx", "18+")
    }

    /** Short tags as providers write them → our code ("GB" is UK, "DEU" is DE…). */
    private val TAG_ALIASES = mapOf(
        "GB" to "UK", "ENG" to "UK", "DEU" to "DE", "GER" to "DE", "AUT" to "AT", "FRA" to "FR", "ITA" to "IT", "ESP" to "ES",
        "POR" to "PT", "NED" to "NL", "TUR" to "TR", "POL" to "PL", "USA" to "US", "EX-YU" to "EXYU", "MULTI-LANG" to "MULTI",
        "ARA" to "AR", "ARAB" to "AR", "UHD" to "4K", "XXX" to "ADULT", "LAT" to "LATAM", "AF" to "AFR"
    )

    /** Regions that providers split by country ("EU | GERMANY …"). */
    private val REGIONS = setOf("EU", "EUR", "EUROPE", "INT", "WORLD", "ASIA", "AS", "AM", "NA", "SA")

    /** Two-letter words too common to read as a country when they stand alone in a name. */
    private val TOO_COMMON = setOf("TV", "HD", "SD", "FM", "ON", "OF", "IN", "IT", "TO", "IS", "NO", "ME", "AL", "ID", "MY", "BE", "AS", "AM", "AT", "DE", "ES", "SA", "NA")

    fun name(code: String): String = NAMES[code] ?: code

    /** True for the codes Regions knows (countries, languages, VIP…). */
    fun known(code: String): Boolean = code in NAMES

    private fun code(tag: String): String? {
        val t = tag.uppercase().trim()
        val c = TAG_ALIASES[t] ?: t
        return c.takeIf { it in NAMES }
    }

    private val front = Regex("""^\s*[\[(]?([A-Za-z0-9+\-]{2,6})[\])]?\s*[|:\-–]\s*(.*)$""")
    private val endTag = Regex("""(?:[\s\-–|]|^)[\[(]?([A-Za-z]{2,3})[\])]?\s*$""")
    private val anyBracket = Regex("""[\[(]([A-Za-z0-9+\-]{2,6})[\])]""")
    private val wordSplit = Regex("""[^\p{L}\p{N}+]+""")

    /**
     * The folder for a group or channel name: a country, language or kind code, or null when the
     * name says none. Tags in front win, then a tag in brackets or at the end, then written-out names.
     */
    fun of(name: String): String? {
        val n = name.trim()
        // "AT | ORF", "[DE] Kino", "EU | GERMANY SPORTS" (a region split by country)
        front.find(n)?.let { m ->
            val head = m.groupValues[1].uppercase()
            val rest = m.groupValues[2]
            if (head in REGIONS) { words(rest)?.let { return it }; code(rest.substringBefore(' '))?.let { return it } }
            code(head)?.let { return it }
        }
        // "Movies (UK)", "News [FR]"
        anyBracket.findAll(n).forEach { m -> code(m.groupValues[1])?.let { return it } }
        // "Sport DE", "Kids - FR"
        endTag.find(n)?.groupValues?.get(1)?.uppercase()?.let { t -> if (t !in TOO_COMMON || t in setOf("DE", "AT", "IT", "ES")) code(t)?.let { return it } }
        // "Germany Sports", "Deutschland HD", "Österreich", "UK Entertainment"
        return words(n)
    }

    private fun words(text: String): String? {
        val plain = withoutAccents(text.lowercase())
        val tokens = plain.split(wordSplit).filter { it.isNotEmpty() }
        // Two-word names first ("united kingdom", "south africa"), then single words.
        for (i in 0 until tokens.size - 1) WORDS[tokens[i] + " " + tokens[i + 1]]?.let { return it }
        for (t in tokens) WORDS[t]?.let { return it }
        // A short code standing as its own word at the start ("UK Entertainment", "USA Sports").
        tokens.firstOrNull()?.uppercase()?.let { t -> if (t.length in 2..4 && t !in TOO_COMMON) code(t)?.let { return it } }
        return null
    }

    private fun withoutAccents(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                'ä', 'á', 'à', 'â', 'ã', 'å' -> 'a'; 'ö', 'ó', 'ò', 'ô', 'õ' -> 'o'; 'ü', 'ú', 'ù', 'û' -> 'u'
                'é', 'è', 'ê', 'ë' -> 'e'; 'í', 'ì', 'î', 'ï' -> 'i'; 'ç' -> 'c'; 'ñ' -> 'n'; 'ß' -> 's'; 'č' -> 'c'; 'š' -> 's'; 'ž' -> 'z'
                else -> c
            }
        )
    }

    // ── Folders ──────────────────────────────────────────────────────────────────────────

    data class Folder(val key: String, val title: String, val groups: List<Group>)

    /** The folders for a list, or [flat] when the list doesn't fall into countries (then show it plain). */
    data class Plan(val folders: List<Folder>, val flat: Boolean)

    const val OTHER = "~other"
    const val CUSTOM = "custom:"

    /**
     * Folders for [groups]. [custom] puts a group into a folder you named (group id → folder name);
     * [first] groups go in a folder of their own at the top (e.g. the sorted ones).
     */
    fun plan(groups: List<Group>, custom: Map<String, String> = emptyMap(), first: Pair<String, (Group) -> Boolean>? = null): Plan {
        val byKey = LinkedHashMap<String, MutableList<Group>>()
        var placed = 0
        for (g in groups) {
            val own = custom[g.id]?.trim()?.takeIf { it.isNotEmpty() }
            val k = when {
                first != null && first.second(g) -> "~first"
                own != null -> CUSTOM + own
                else -> of(g.name) ?: OTHER
            }
            if (k != OTHER) placed++
            byKey.getOrPut(k) { ArrayList() } += g
        }
        val folders = byKey.map { (k, gs) ->
            val title = when {
                k == "~first" -> first!!.first
                k == OTHER -> "Other"
                k.startsWith(CUSTOM) -> k.removePrefix(CUSTOM)
                else -> name(k)
            }
            Folder(k, title, gs)
        }.sortedBy { if (it.key == "~first") 0 else if (it.key == OTHER) 2 else 1 }
        // Folders help only when most groups fall into a few of them: a short or untagged list stays plain.
        val real = folders.count { it.key != OTHER && it.key != "~first" }
        val flat = real < 2 || placed < groups.size * 0.5
        return Plan(folders, flat)
    }

    /** A group's name inside its folder, without the country part: "EU | GERMANY SPORTS" → "Sports". */
    fun shortName(group: Group, folderKey: String): String {
        var n = group.name.trim()
        if (folderKey == OTHER || folderKey.startsWith(CUSTOM) || folderKey == "~first") return n.ifBlank { "Channels" }
        front.find(n)?.let { m ->
            val head = m.groupValues[1].uppercase()
            n = m.groupValues[2].trim()
            if (head in REGIONS) n = n.split(' ').drop(1).joinToString(" ").ifBlank { n }
        }
        // Drop a written-out country at the start or end ("Germany Sports" → "Sports").
        WORDS.filterValues { it == folderKey }.keys.sortedByDescending { it.length }.forEach { w ->
            val e = Regex.escape(w)
            val rx = Regex("(?i)^$e$|^$e\\s+|\\s+$e$")
            n = n.replace(rx, "").trim()
        }
        n = n.replace(Regex("""\s*[\[(][A-Za-z]{2,3}[\])]\s*$"""), "").replace(Regex("""\s+[A-Z]{2}$"""), "").trim()
        if (n.isBlank()) return "All channels"
        return if (n == n.uppercase() && n.any { it.isLetter() }) n.lowercase().split(' ').joinToString(" ") { w ->
            if (w in setOf("tv", "hd", "uhd", "fhd", "vip", "ppv", "nba", "nfl", "nhl", "ufc", "dazn", "bbc", "itv", "orf", "rtl", "zdf", "ard")) w.uppercase()
            else w.replaceFirstChar { it.uppercase() }
        } else n
    }
}
