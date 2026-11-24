package app.avoniotv.core

/**
 * The language or region tag at the start of a provider's group names ("DE | SPORT", "EU | ES GENERAL"),
 * so whole languages and countries can be hidden at once. One copy for the TV box, the iPad and avonioTV Home.
 */
object ChannelTags {
    /** "DE" → "DE · German": the language or region behind a group's tag, where we know it. */
    fun label(tag: String): String {
        // A country inside a region ("EU | ES") reads as the country: "ES · Spanish".
        if (" | " in tag) {
            val country = tag.substringAfter(" | ")
            return TAG_NAMES[country]?.let { "$country · $it" } ?: country
        }
        val name = TAG_NAMES[tag] ?: return tag
        return "$tag · $name"
    }

    private val TAG_NAMES = mapOf(
        "AF" to "Africa", "AS" to "Asia", "EU" to "Europe", "LATAM" to "Latin America", "AM" to "Americas", "NA" to "North America",
        "SA" to "South America", "INT" to "International", "VIP" to "Events & sport", "AR" to "Arabic", "DE" to "German",
        "AT" to "Austria", "CH" to "Switzerland", "UK" to "UK · English", "EN" to "English", "US" to "USA · English",
        "CA" to "Canada", "AU" to "Australia", "IE" to "Ireland", "FR" to "French", "BE" to "Belgium", "NL" to "Dutch",
        "IT" to "Italian", "ES" to "Spanish", "PT" to "Portuguese", "BR" to "Brazil", "MX" to "Mexico", "TR" to "Turkish",
        "PL" to "Polish", "RO" to "Romanian", "HU" to "Hungarian", "CZ" to "Czech", "SK" to "Slovak", "GR" to "Greek",
        "AL" to "Albanian", "EXYU" to "Ex-Yugoslavia", "EX-YU" to "Ex-Yugoslavia", "BG" to "Bulgarian", "RU" to "Russian",
        "UA" to "Ukrainian", "SE" to "Swedish", "NO" to "Norwegian", "DK" to "Danish", "FI" to "Finnish", "IN" to "India",
        "PK" to "Pakistan", "BD" to "Bangladesh", "CN" to "Chinese", "JP" to "Japanese", "KR" to "Korean", "PH" to "Philippines",
        "IR" to "Persian", "KU" to "Kurdish", "AFG" to "Afghanistan", "IL" to "Hebrew", "4K" to "4K", "ADULT" to "Adult",
        "MULTI" to "Multi-language", "MULTI-LANG" to "Multi-language", "OTHER" to "Other"
    )

    private val REGIONS = setOf("EU", "LATAM", "ASIA", "AS", "AM", "NA", "SA", "INT")

    /** "AR | MBC 1" → AR, "VIP | SPORTS" → VIP, "EU | UK GENERAL" → EU | UK (regions are split by country). */
    fun of(name: String): String? {
        val parts = name.split('|', ':').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val head = parts[0].removeSurrounding("[", "]").uppercase()
            if (head.length in 2..6 && head.all { it.isLetterOrDigit() || it == '-' || it == '+' }) {
                if (head in REGIONS) {
                    val country = parts[1].substringBefore(' ').uppercase()
                    if (country.length in 2..12 && country.all { it.isLetter() }) return "$head | $country"
                }
                return head
            }
        }
        return OnDemand.categoryTag(name)
    }

    /**
     * The quick switches: one per language or country, plus one for a whole region ("EU") when it's split by
     * country. Biggest first, "Other" last. Each is the tag and the group ids under it.
     */
    fun switches(groups: List<Group>): List<Pair<String, List<String>>> {
        val byTag = groups.groupBy { of(it.name) ?: "Other" }
        val regions = byTag.keys.filter { " | " in it }.groupBy { it.substringBefore(" | ") }
            .filter { it.value.size > 1 }
            .map { (r, keys) -> r to keys.flatMap { k -> byTag[k].orEmpty() } }
        return (byTag.entries.map { it.key to it.value } + regions)
            .sortedWith(compareBy<Pair<String, List<Group>>> { it.first == "Other" }.thenByDescending { it.second.size })
            .map { it.first to it.second.map { g -> g.id } }
    }

    /** The folded sections of the group list: one per language or region. */
    fun sections(groups: List<Group>): List<Pair<String, List<Group>>> =
        groups.groupBy { of(it.name)?.substringBefore(" | ") ?: "Other" }.entries
            .sortedWith(compareBy<Map.Entry<String, List<Group>>> { it.key == "Other" }.thenByDescending { it.value.size })
            .map { it.key to it.value }
}
