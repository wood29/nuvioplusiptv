package app.avoniotv.core

/**
 * The Sports guide: finds the sport in the TV guide across all your channels (football, F1,
 * tennis, ice hockey…), the same match on several channels shown once. Shared by the TV, VR and
 * iPad apps.
 */
object SportsGuide {

    enum class Sport(val label: String, words: String) {
        FOOTBALL("Football", "football|soccer|fußball|fussball|voetbal|calcio|fútbol|futbol|bundesliga|premier league|champions league|europa league|conference league|la ?liga|serie a|ligue 1|eredivisie|dfb.?pokal|fa cup|efl|uefa|fifa|world cup|copa|mls|ö?fb.?cup|2\\. liga|super league|primeira liga|süper lig"),
        MOTOR("Motorsport", "formula 1|formel 1|f1|formula one|grand prix|motogp|moto2|moto3|nascar|indycar|rallye|rally|dtm|wec|le mans|formula e|motorsport"),
        TENNIS("Tennis", "tennis|atp|wta|wimbledon|roland garros|us open|australian open|davis cup|billie jean king"),
        HOCKEY("Ice hockey", "ice hockey|eishockey|ijshockey|nhl|khl|ice hockey league"),
        BASKETBALL("Basketball", "basketball|nba|euroleague|basket|wnba"),
        AMERICAN("American football", "nfl|american football|super bowl|college football|ncaa football"),
        WINTER("Winter sport", "ski|skispringen|ski jumping|biathlon|langlauf|cross.country|slalom|abfahrt|downhill|super.g|snowboard|skeleton|bobsleigh|rodeln|luge|eisschnelllauf|speed skating|eiskunstlauf|figure skating"),
        CYCLING("Cycling", "tour de france|giro d'italia|vuelta|radsport|cycling|wielrennen|cyclisme|paris.roubaix"),
        GOLF("Golf", "golf|pga|ryder cup|lpga|dp world tour"),
        FIGHT("Fight sport", "ufc|boxing|boxen|boxe|mma|wrestling|wwe|aew|kickboxing|bellator"),
        RUGBY("Rugby", "rugby|six nations"),
        HANDBALL("Handball", "handball"),
        VOLLEYBALL("Volleyball", "volleyball|beach.?volley"),
        DARTS("Darts", "darts|pdc"),
        SNOOKER("Snooker", "snooker"),
        BASEBALL("Baseball", "baseball|mlb|world series"),
        CRICKET("Cricket", "cricket|ipl|test match"),
        ATHLETICS("Athletics", "athletics|leichtathletik|atletiek|marathon|diamond league"),
        OTHER("Sport", "");

        internal val words: String = words
        internal val pattern: Regex? = if (words.isEmpty()) null else Regex("(?i)(?<![\\p{L}\\d])($words)(?![\\p{L}\\d])")
    }

    /** All sports' words at once: most programmes are ruled out with this one check. */
    private val anySport: Regex by lazy {
        Regex("(?i)(?<![\\p{L}\\d])(" + Sport.values().filter { it.words.isNotEmpty() }.joinToString("|") { it.words } + ")(?![\\p{L}\\d])")
    }

    /** A sport programme; [channels] are every channel showing it (the first is the best one). */
    data class Event(
        val programme: Programme,
        val channels: List<Channel>,
        val sport: Sport,
        val live: Boolean,
        /** "Bayern" and "Dortmund" from "Bundesliga: Bayern – Dortmund", for Follow. */
        val sides: List<String>,
        val favourite: Boolean,
        /** Highlights, magazines, talk or an old replay: hidden under "Matches only". */
        val magazine: Boolean = false,
    ) {
        val channel: Channel get() = channels.first()
        val key: String get() = channel.id + "@" + programme.startMs
    }

    private val sportCategory = Regex("(?i)sport|football|soccer|fußball|fussball|voetbal|motor|tennis|hockey|basketball|racing")
    private val liveMark = Regex("(?i)(?<![\\p{L}])(live|en direct|en vivo|in diretta)(?![\\p{L}])|\\(l\\)|ᴸᴵⱽᴱ|🔴")
    private val notMatches = Regex("(?i)highlights?|zusammenfassung|magazin|magazine|news|nachrichten|journal|review|rückblick|samenvatting|replay|wiederholung|classics?|doku|documentary|quiz|talk")
    private val versus = Regex("\\s+(?:-|–|—|vs\\.?|v\\.?|gegen|x)\\s+", RegexOption.IGNORE_CASE)

    /** Which sport a programme is, or null if it isn't sport. */
    fun sportOf(p: Programme, ch: Channel? = null): Sport? {
        val text = p.title + " " + (p.category ?: "")
        val cat = p.category
        val maybe = anySport.containsMatchIn(text)
        if (!maybe && (cat == null || !sportCategory.containsMatchIn(cat))) return null
        if (maybe) Sport.values().firstOrNull { it.pattern?.containsMatchIn(text) == true }?.let { return it }
        if (cat != null && sportCategory.containsMatchIn(cat)) {
            return Sport.values().firstOrNull { it.pattern?.containsMatchIn(p.description.orEmpty()) == true } ?: Sport.OTHER
        }
        return null
    }

    /** "Bundesliga: FC Bayern – Borussia Dortmund (Live)" → ["FC Bayern", "Borussia Dortmund"]. */
    fun sidesOf(title: String): List<String> {
        val clean = title.replace(liveMark, " ").replace(bracketsRx, " ")
        val afterLabel = clean.substringAfterLast(": ").substringAfterLast(" | ")
        val parts = afterLabel.split(versus)
        if (parts.size != 2) return emptyList()
        return parts.map { it.trim().trim('-', '–', ',', '.').trim() }.filter { it.length in 2..40 }.takeIf { it.size == 2 }.orEmpty()
    }

    /** The competition before a colon ("Bundesliga" in "Bundesliga: Bayern – Dortmund"). */
    fun competitionOf(title: String): String? =
        title.substringBefore(": ", "").trim().takeIf { it.length in 3..40 }

    /**
     * Every sport programme from [fromMs] to [toMs] on [channels], live matches first within the
     * same start time; the same programme on several channels (HD, backups) becomes one event.
     */
    fun events(guide: Guide, channels: List<Channel>, fromMs: Long, toMs: Long, follows: List<String>, nowMs: Long, matchesOnly: Boolean = false): List<Event> {
        val found = LinkedHashMap<String, MutableList<Pair<Channel, Programme>>>()
        val sportOfKey = HashMap<String, Sport>()
        for (ch in channels) {
            var chName: String? = null
            for (p in guide.listings(ch)) {
                if (p.stopMs <= fromMs || p.startMs >= toMs) continue
                // Channel filler (a 12-hour block named like the channel) isn't a match.
                if (p.stopMs - p.startMs > MAX_EVENT_MS) continue
                // The cheap sport check first; names are only compared for real candidates.
                val sport = sportOf(p, ch) ?: continue
                val title = Guide.normalize(p.title)
                if (title == (chName ?: Guide.normalize(ch.name).also { chName = it })) continue
                if (matchesOnly && isMagazine(p.title, nowMs)) continue
                val key = title + "@" + p.startMs
                found.getOrPut(key) { mutableListOf() } += ch to p
                if (key !in sportOfKey) sportOfKey[key] = sport
            }
        }
        val lowerFollows = follows.map { it.lowercase() }.filter { it.isNotBlank() }
        return found.map { (key, list) ->
            val sorted = list.sortedWith(compareBy<Pair<Channel, Programme>> { liveScore(it.first.name) }.thenBy { it.first.number })
            val p = sorted.first().second
            val text = (p.title + " " + (p.description ?: "")).lowercase()
            Event(
                programme = p,
                channels = sorted.map { it.first }.distinctBy { it.id },
                sport = sportOfKey[key] ?: Sport.OTHER,
                live = liveMark.containsMatchIn(p.title),
                sides = sidesOf(p.title),
                favourite = lowerFollows.any { it in text || it == (sportOfKey[key]?.label?.lowercase()) },
                magazine = isMagazine(p.title, nowMs),
            )
        }.sortedWith(compareBy<Event> { it.programme.startMs }.thenBy { if (it.favourite) 0 else 1 }.thenBy { if (it.live) 0 else 1 })
    }

    private fun isMagazine(title: String, nowMs: Long) = notMatches.containsMatchIn(title) || isOldReplay(title, nowMs)

    /** Does a team, competition or sport you follow appear in this event? */
    fun follows(e: Event, follows: List<String>): Boolean {
        val text = (e.programme.title + " " + (e.programme.description ?: "")).lowercase()
        return follows.any { f -> f.isNotBlank() && (f.lowercase() in text || f.equals(e.sport.label, ignoreCase = true)) }
    }

    private const val MAX_EVENT_MS = 5 * 3_600_000L
    private val bracketsRx = Regex("\\(.*?\\)|\\[.*?]")
    private val year = Regex("(?<![\\d/])(19|20)\\d\\d(?![\\d/–-])")

    /** "Boxing … 28.03.2021" on a 2026 evening is a replay, not tonight's fight. */
    private fun isOldReplay(title: String, nowMs: Long): Boolean {
        val thisYear = (1970 + nowMs / 31_556_952_000L).toInt()
        return year.findAll(title).any { it.value.toInt() < thisYear }
    }

    /** Prefers the sharpest version of a channel: FHD/UHD/HD over SD. */
    private fun liveScore(name: String): Int {
        val n = name.uppercase()
        return when {
            "UHD" in n || "4K" in n -> 0
            "FHD" in n -> 1
            "HD" in n -> 2
            "SD" in n -> 4
            else -> 3
        }
    }
}
