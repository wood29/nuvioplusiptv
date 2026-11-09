package com.nuvio.tv.livetv.data

/**
 * Nuvio Plus: the country (or region) a provider group belongs to, read from the prefix most
 * providers put in front of their group names: "UK | Sports", "|DE| News", "AT: ORF",
 * "[FR] Cinéma", "US - Locals", "EXYU ▎ Sport". Used to hide or show a whole country at once.
 * A region prefix ("EU | AUSTRIA", "EU | UK CINEMA") gives way to the country named after it.
 */
object GroupCountry {
    private val PREFIX = Regex("""^\s*[\[(|│┃▎]?\s*([A-Za-z]{2,4})\s*[\])|│┃▎:\-–—•»]\s*(.*)$""")
    private val FIRST_WORD = Regex("""^[\[(|│┃▎\s]*([A-Za-z]+)""")

    /** Prefixes that describe quality or type, not a country. */
    private val NOT_COUNTRY = setOf("HD", "FHD", "UHD", "SD", "VIP", "PPV", "VOD", "TV", "LIVE", "NEW", "TOP", "THE", "ALL")

    /** Prefixes for a whole region: the country is the next word. */
    private val REGIONS = setOf("EU", "EUR", "AF", "AFR", "AS", "ASIA", "AM", "LAT", "LATM", "ARB", "INT", "WW", "MENA")

    /** Country names written out, to their usual short form (so "EU | GERMANY…" and "DE | …" match). */
    private val NAMES = mapOf(
        "GERMANY" to "DE", "DEUTSCHLAND" to "DE", "AUSTRIA" to "AT", "OSTERREICH" to "AT", "SWITZERLAND" to "CH",
        "FRANCE" to "FR", "ITALY" to "IT", "ITALIA" to "IT", "SPAIN" to "ES", "ESPANA" to "ES", "PORTUGAL" to "PT",
        "NETHERLANDS" to "NL", "HOLLAND" to "NL", "BELGIUM" to "BE", "POLAND" to "PL", "IRELAND" to "IE",
        "TURKEY" to "TR", "GREECE" to "GR", "SWEDEN" to "SE", "NORWAY" to "NO", "DENMARK" to "DK", "FINLAND" to "FI",
        "USA" to "US", "CANADA" to "CA", "ENGLAND" to "UK", "BRITAIN" to "UK", "SCOTLAND" to "UK",
        "ALBANIA" to "AL", "ROMANIA" to "RO", "HUNGARY" to "HU", "CZECH" to "CZ", "CROATIA" to "HR", "SERBIA" to "RS"
    )

    /** The group's country in capitals ("UK", "DE"), or null if it has none. */
    fun of(groupTitle: String): String? {
        val m = PREFIX.find(groupTitle) ?: return null
        val p = m.groupValues[1].uppercase()
        if (p in REGIONS) {
            val w = FIRST_WORD.find(m.groupValues[2])?.groupValues?.get(1)?.uppercase() ?: return p
            NAMES[w]?.let { return it }
            if (w.length in 2..3 && w !in NOT_COUNTRY) return w
            return p
        }
        return if (p in NOT_COUNTRY) null else NAMES[p] ?: p
    }
}
