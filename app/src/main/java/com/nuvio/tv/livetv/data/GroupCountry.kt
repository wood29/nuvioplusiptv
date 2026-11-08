package com.nuvio.tv.livetv.data

/**
 * Nuvio Plus: the country (or region) a provider group belongs to, read from the prefix most
 * providers put in front of their group names: "UK | Sports", "|DE| News", "AT: ORF",
 * "[FR] Cinéma", "US - Locals", "EXYU ▎ Sport". Used to hide or show a whole country at once.
 */
object GroupCountry {
    private val PREFIX = Regex("""^\s*[\[(|│┃▎]?\s*([A-Za-z]{2,4})\s*[\])|│┃▎:\-–—•»]""")

    /** Prefixes that describe quality or type, not a country. */
    private val NOT_COUNTRY = setOf("HD", "FHD", "UHD", "SD", "VIP", "PPV", "VOD", "TV", "LIVE", "NEW", "TOP", "THE", "ALL")

    /** The group's country prefix in capitals ("UK"), or null if it has none. */
    fun of(groupTitle: String): String? {
        val m = PREFIX.find(groupTitle) ?: return null
        val p = m.groupValues[1].uppercase()
        return if (p in NOT_COUNTRY) null else p
    }
}
