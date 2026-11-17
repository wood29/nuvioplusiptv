/*
 * Nuvio Plus On Demand library for iPhone and iPad: written from scratch (see iptvui/LICENSE).
 * The same logic as avonioTV's on the TV box: lists saved on the device, hidden categories,
 * Home rows, My list, where you stopped, and TMDB details with your own key.
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package plus.nuvio.iptv.ui

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import plus.nuvio.iptv.OnDemand
import plus.nuvio.iptv.Source
import plus.nuvio.iptv.Tmdb
import plus.nuvio.iptv.TmdbInfo
import plus.nuvio.iptv.VodCategory
import plus.nuvio.iptv.VodDetails
import plus.nuvio.iptv.VodEpisode
import plus.nuvio.iptv.VodItem
import plus.nuvio.iptv.VodKind


/** Movies and series from the provider, and where you stopped watching. One for the app. */
class VodLibrary internal constructor(private val source: () -> Source?) {

    data class Shelf(
        val loading: Boolean = false,
        val count: Int = 0,
        val error: String? = null,
        val categories: List<VodCategory> = emptyList(),
        val items: List<VodItem> = emptyList(),
        val rows: HomeRows? = null
    ) {
        val byCategory: Map<String, List<VodItem>> by lazy { items.groupBy { it.categoryId } }
        val byTitle: Map<String, List<VodItem>> by lazy { items.groupBy { OnDemand.normalize(it.title) } }
        val recent: List<VodItem> get() = rows?.recent.orEmpty()
    }

    data class HomeRows(
        val recent: List<VodItem>, val top: List<VodItem>, val family: List<VodItem>, val rated: List<VodItem>,
        val biggest: List<Pair<String, List<VodItem>>>
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // A provider's full list can take minutes to arrive.
    private val http = HttpClient(Darwin) { install(HttpTimeout) { requestTimeoutMillis = 600_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 60_000 } }
    private val od = OnDemand(http)
    private val shelves = mapOf(VodKind.MOVIE to MutableStateFlow(Shelf()), VodKind.SERIES to MutableStateFlow(Shelf()))
    private val locks = mapOf(VodKind.MOVIE to Mutex(), VodKind.SERIES to Mutex())
    private val detailCache = HashMap<String, VodDetails>()
    private val categoryCache = HashMap<String, List<VodItem>>()
    private val all = HashMap<VodKind, Pair<List<VodCategory>, List<VodItem>>>()
    private val store = NSUserDefaults.standardUserDefaults

    fun shelf(kind: VodKind): StateFlow<Shelf> = shelves.getValue(kind).asStateFlow()
    private fun xtream() = source() as? Source.Xtream

    /** From memory, else the copy saved on this device (seconds), else the provider. */
    fun load(kind: VodKind, force: Boolean = false) {
        scope.launch {
            locks.getValue(kind).withLock {
                val flow = shelves.getValue(kind)
                if (!force && flow.value.items.isNotEmpty()) return@withLock
                val s = xtream() ?: run { flow.value = Shelf(error = "On Demand needs an Xtream login (server, username, password)."); return@withLock }
                if (!force) {
                    flow.value = Shelf(loading = true)
                    val saved = runCatching { withContext(Dispatchers.Default) { readSaved(kind, s) } }.getOrNull()
                    if (saved != null) {
                        publish(kind, saved.first, saved.second)
                        if (nowMs() - saved.third < 12 * 3_600_000L) return@withLock
                        fetch(kind, s, quiet = true)
                        return@withLock
                    }
                }
                fetch(kind, s, quiet = false)
            }
        }
    }

    private suspend fun fetch(kind: VodKind, s: Source.Xtream, quiet: Boolean) {
        val flow = shelves.getValue(kind)
        val what = if (kind == VodKind.MOVIE) "movies" else "series"
        if (!quiet) flow.value = Shelf(loading = true)
        val cats = runCatching { withContext(Dispatchers.Default) { od.categories(s, kind) } }.getOrElse {
            if (!quiet) flow.value = Shelf(error = "Couldn't load $what: ${it.message ?: "no answer"}")
            return
        }
        if (!quiet) flow.value = flow.value.copy(categories = cats.filter { it.id !in hidden(kind) })
        runCatching {
            val items = withContext(Dispatchers.Default) { od.items(s, kind) { n -> if (!quiet) flow.value = flow.value.copy(count = n) } }
            val kept = cats.filter { c -> items.any { it.categoryId == c.id } }.ifEmpty { cats }
            publish(kind, kept, items)
            withContext(Dispatchers.Default) { runCatching { save(kind, s, kept, items) } }
        }.onFailure {
            if (!quiet) flow.value = flow.value.copy(loading = false, error = "Couldn't read the full list of $what; pick a category.")
        }
    }

    /** Shows only the categories you haven't hidden; Home, search and "More like this" use these too. */
    private suspend fun publish(kind: VodKind, cats: List<VodCategory>, items: List<VodItem>) {
        all[kind] = cats to items
        val hide = hidden(kind)
        shelves.getValue(kind).value = withContext(Dispatchers.Default) {
            val vc = cats.filter { it.id !in hide }
            val vi = if (hide.isEmpty()) items else items.filter { it.categoryId !in hide }
            Shelf(categories = vc, items = vi, rows = homeRows(vi, vc)).also { it.byCategory }
        }
    }

    suspend fun category(kind: VodKind, id: String): List<VodItem> {
        if (id in hidden(kind)) return emptyList()
        val shelf = shelves.getValue(kind).value
        if (shelf.items.isNotEmpty()) return shelf.byCategory[id].orEmpty()
        categoryCache["${kind.name}:$id"]?.let { return it }
        val s = xtream() ?: return emptyList()
        return withContext(Dispatchers.Default) { od.items(s, kind, categoryId = id) }.also { categoryCache["${kind.name}:$id"] = it }
    }

    suspend fun details(item: VodItem): VodDetails? {
        detailCache[item.key]?.let { return it }
        val s = xtream() ?: return null
        return runCatching { withContext(Dispatchers.Default) { od.details(s, item) } }.getOrNull()?.also { detailCache[item.key] = it }
    }

    fun movieUrl(item: VodItem) = xtream()?.let { od.movieUrl(it, item) }
    fun episodeUrl(ep: VodEpisode) = xtream()?.let { od.episodeUrl(it, ep) }

    // ---- hidden categories ----

    fun hidden(kind: VodKind): Set<String> =
        store.stringForKey("np_vod_hidden_${kind.name}")?.split(FLD)?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    fun allCategories(kind: VodKind): List<VodCategory> = all[kind]?.first.orEmpty()
    fun counts(kind: VodKind): Map<String, Int> = all[kind]?.second.orEmpty().groupingBy { it.categoryId }.eachCount()

    fun setHidden(kind: VodKind, ids: Set<String>) {
        store.setObject(ids.joinToString(FLD), "np_vod_hidden_${kind.name}")
        scope.launch { locks.getValue(kind).withLock { all[kind]?.let { (c, i) -> publish(kind, c, i) } } }
    }

    // ---- My list ----

    private val _myList = MutableStateFlow(readItems("np_vod_mylist"))
    val myList: StateFlow<List<VodItem>> = _myList.asStateFlow()

    fun toggleMyList(item: VodItem) {
        val list = if (_myList.value.any { it.key == item.key }) _myList.value.filterNot { it.key == item.key } else listOf(item) + _myList.value
        _myList.value = list.take(200)
        store.setObject(_myList.value.joinToString(REC) { itemFields(it) }, "np_vod_mylist")
    }

    private fun itemFields(i: VodItem) = listOf(i.id, i.kind.name, i.name, i.categoryId, i.poster.orEmpty(), (i.year ?: 0).toString(), i.container.orEmpty()).joinToString(FLD)
    private fun readItems(key: String): List<VodItem> = store.stringForKey(key)?.split(REC)?.mapNotNull { line ->
        val f = line.split(FLD)
        if (f.size < 7) return@mapNotNull null
        val kind = runCatching { VodKind.valueOf(f[1]) }.getOrNull() ?: return@mapNotNull null
        VodItem(f[0], kind, f[2], f[3], poster = f[4].ifBlank { null }, year = f[5].toIntOrNull()?.takeIf { it > 0 }, container = f[6].ifBlank { null })
    }.orEmpty()

    // ---- TMDB (optional, with your own key) ----

    var tmdbKey: String?
        get() = store.stringForKey("np_tmdb_key")?.takeIf { it.isNotBlank() }
        set(v) { store.setObject(v ?: "", "np_tmdb_key") }
    private var tmdb: Tmdb? = null
    private var tmdbFor: String? = null

    private fun tmdb(): Tmdb? {
        val key = tmdbKey?.trim() ?: return null
        if (key != tmdbFor) { tmdb = Tmdb(http, key); tmdbFor = key }
        return tmdb
    }

    suspend fun extra(item: VodItem, details: VodDetails?): TmdbInfo? {
        val t = tmdb() ?: return null
        val id = (details?.tmdbId ?: item.tmdbId)?.toIntOrNull()
        return runCatching { withContext(Dispatchers.Default) { t.info(item.kind, id, item.title, item.year) } }.getOrNull()
    }

    suspend fun related(info: TmdbInfo, item: VodItem): List<VodItem> = withContext(Dispatchers.Default) {
        Tmdb.match(info.related, shelves.getValue(item.kind).value.byTitle, exclude = item.key).take(20)
    }

    suspend fun checkTmdb(key: String): Boolean = Tmdb(http, key.trim()).check()

    // ---- where you stopped (this device) ----

    data class Progress(val positionMs: Long, val durationMs: Long) {
        val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val finished: Boolean get() = durationMs > 0 && positionMs > durationMs * 0.94
    }
    data class Resume(val item: VodItem, val episodeId: String?, val label: String?, val progress: Progress)

    private val _resume = MutableStateFlow(readResume())
    val resume: StateFlow<List<Resume>> = _resume.asStateFlow()

    fun progress(key: String): Progress? = store.stringForKey("np_vod_p_$key")?.split('|')?.let {
        if (it.size < 2) null else Progress(it[0].toLongOrNull() ?: 0, it[1].toLongOrNull() ?: 0)
    }

    fun save(item: VodItem, episodeId: String?, label: String?, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val p = Progress(positionMs, durationMs)
        store.setObject("$positionMs|$durationMs", "np_vod_p_${episodeId?.let { "e:$it" } ?: item.key}")
        val others = _resume.value.filterNot { it.item.key == item.key }
        val list = (if (p.finished && episodeId == null) others else listOf(Resume(item, episodeId, label, p)) + others).take(30)
        writeResume(list)
    }

    fun forget(item: VodItem) { writeResume(_resume.value.filterNot { it.item.key == item.key }) }

    private fun writeResume(list: List<Resume>) {
        _resume.value = list
        store.setObject(list.joinToString(REC) { r ->
            listOf(r.item.id, r.item.kind.name, r.item.name, r.item.categoryId, r.item.poster.orEmpty(), (r.item.year ?: 0).toString(),
                r.item.container.orEmpty(), r.episodeId.orEmpty(), r.label.orEmpty(), r.progress.positionMs.toString(), r.progress.durationMs.toString()
            ).joinToString(FLD)
        }, KEY_RESUME)
        store.synchronize()
    }

    private fun readResume(): List<Resume> = store.stringForKey(KEY_RESUME)?.split(REC)?.mapNotNull { line ->
        val f = line.split(FLD)
        if (f.size < 11) return@mapNotNull null
        val kind = runCatching { VodKind.valueOf(f[1]) }.getOrNull() ?: return@mapNotNull null
        Resume(
            VodItem(f[0], kind, f[2], f[3], poster = f[4].ifBlank { null }, year = f[5].toIntOrNull()?.takeIf { it > 0 }, container = f[6].ifBlank { null }),
            f[7].ifBlank { null }, f[8].ifBlank { null }, Progress(f[9].toLongOrNull() ?: 0, f[10].toLongOrNull() ?: 0)
        )
    }.orEmpty()

    // ---- the lists saved on this device ----

    private fun path(kind: VodKind): String? {
        val dir = NSFileManager.defaultManager.URLsForDirectory(NSCachesDirectory, NSUserDomainMask).firstOrNull() as? NSURL ?: return null
        return dir.path?.let { "$it/np_vod_${kind.name.lowercase()}.tsv" }
    }
    private fun owner(s: Source.Xtream) = (s.server.trim().trimEnd('/') + "|" + s.username).hashCode().toString()
    private fun clean(v: String?) = v.orEmpty().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun save(kind: VodKind, s: Source.Xtream, cats: List<VodCategory>, items: List<VodItem>) {
        val p = path(kind) ?: return
        val sb = StringBuilder(items.size * 160)
        sb.append("npvod1\t").append(owner(s)).append('\t').append(nowMs()).append('\n')
        cats.forEach { sb.append("C\t").append(clean(it.id)).append('\t').append(clean(it.name)).append('\n') }
        items.forEach { i ->
            sb.append("I\t").append(clean(i.id)).append('\t').append(clean(i.name)).append('\t').append(clean(i.categoryId))
                .append('\t').append(clean(i.poster)).append('\t').append(i.rating?.toString().orEmpty())
                .append('\t').append(i.year?.toString().orEmpty()).append('\t').append(i.added)
                .append('\t').append(clean(i.container)).append('\t').append(clean(i.tmdbId)).append('\t').append(clean(i.genre)).append('\n')
        }
        NSString.create(string = sb.toString()).writeToFile(p, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    /** Categories, titles and when they were saved; null without a copy for this login. */
    private fun readSaved(kind: VodKind, s: Source.Xtream): Triple<List<VodCategory>, List<VodItem>, Long>? {
        val p = path(kind) ?: return null
        val text = NSString.stringWithContentsOfFile(p, NSUTF8StringEncoding, null) ?: return null
        val cats = ArrayList<VodCategory>()
        val items = ArrayList<VodItem>()
        val shared = HashMap<String, String>()
        fun share(v: String) = shared.getOrPut(v) { v }
        var savedAt = 0L
        var first = true
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val f = line.split('\t')
            if (first) {
                first = false
                if (f.getOrNull(0) != "npvod1" || f.getOrNull(1) != owner(s)) return null
                savedAt = f.getOrNull(2)?.toLongOrNull() ?: 0
                continue
            }
            when (f[0]) {
                "C" -> if (f.size >= 3) cats += VodCategory(f[1], f[2], kind)
                "I" -> if (f.size >= 11) items += VodItem(
                    id = f[1], kind = kind, name = f[2], categoryId = share(f[3]), poster = f[4].ifEmpty { null },
                    rating = f[5].toDoubleOrNull(), year = f[6].toIntOrNull(), added = f[7].toLongOrNull() ?: 0,
                    container = f[8].ifEmpty { null }?.let(::share), tmdbId = f[9].ifEmpty { null }, genre = f[10].ifEmpty { null }?.let(::share)
                )
            }
        }
        return if (items.isEmpty()) null else Triple(cats, items, savedAt)
    }

    private companion object {
        const val KEY_RESUME = "np_vod_resume"
        const val REC = "\u001E"
        const val FLD = "\u001F"
        val FAMILY = Regex("(?i)anim|family|famil|kids|kinder|children|cartoon|disney|pixar")

        fun homeRows(items: List<VodItem>, categories: List<VodCategory>): HomeRows {
            val catNames = categories.associate { it.id to it.name }
            val year = 1970 + (nowMs() / 31_556_952_000L).toInt()
            val withPoster = items.filter { !it.poster.isNullOrBlank() }
            fun List<VodItem>.uniq(n: Int) = asSequence().distinctBy { it.title.lowercase() }.take(n).toList()
            val recent = items.filter { it.added > 0 }.sortedByDescending { it.added }.uniq(60)
            val top = withPoster.filter { (it.rating ?: 0.0) >= 6.5 && (it.year ?: 0) >= year - 2 }
                .sortedWith(compareByDescending<VodItem> { it.rating }.thenByDescending { it.added }).uniq(10)
            val family = withPoster.filter { FAMILY.containsMatchIn(it.genre.orEmpty()) || FAMILY.containsMatchIn(catNames[it.categoryId].orEmpty()) }
                .sortedByDescending { it.added }.uniq(30)
            val rated = withPoster.filter { (it.rating ?: 0.0) >= 7.5 }.sortedByDescending { it.added }.uniq(30)
            val biggest = withPoster.groupBy { it.categoryId }.entries.sortedByDescending { it.value.size }.take(5).map { (id, list) ->
                (catNames[id] ?: "More") to list.sortedByDescending { it.added }.uniq(30)
            }
            return HomeRows(recent, top, family, rated, biggest)
        }
    }
}
