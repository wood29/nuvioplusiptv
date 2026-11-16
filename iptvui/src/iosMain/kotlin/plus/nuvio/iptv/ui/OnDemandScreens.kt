/*
 * Nuvio Plus On Demand for iPhone and iPad: written from scratch (see iptvui/LICENSE).
 * The same design as avonioTV's On Demand on the TV box, made for touch:
 *  - Movies / Series: categories (sidebar on iPad, chips on iPhone), posters, search
 *  - details: description, Play / Resume / Start over, seasons and episodes
 *  - player: tap for controls, ‑10 s / +30 s, a seek bar, sound and subtitles; resumes later
 */
package plus.nuvio.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSUserDefaults
import plus.nuvio.iptv.OnDemand
import plus.nuvio.iptv.Source
import plus.nuvio.iptv.VodCategory
import plus.nuvio.iptv.VodDetails
import plus.nuvio.iptv.VodEpisode
import plus.nuvio.iptv.VodItem
import plus.nuvio.iptv.VodKind

// ---------------------------------------------------------------- the library

/** Movies and series from the provider, and where you stopped watching. One for the app. */
class VodLibrary internal constructor(private val source: () -> Source?) {

    data class Shelf(
        val loading: Boolean = false,
        val count: Int = 0,
        val error: String? = null,
        val categories: List<VodCategory> = emptyList(),
        val items: List<VodItem> = emptyList()
    ) {
        val recent: List<VodItem> by lazy { items.filter { it.added > 0 }.sortedByDescending { it.added }.take(60) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // A provider's full list can take minutes to arrive.
    private val http = HttpClient(Darwin) { install(HttpTimeout) { requestTimeoutMillis = 600_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 60_000 } }
    private val od = OnDemand(http)
    private val shelves = mapOf(VodKind.MOVIE to MutableStateFlow(Shelf()), VodKind.SERIES to MutableStateFlow(Shelf()))
    private val locks = mapOf(VodKind.MOVIE to Mutex(), VodKind.SERIES to Mutex())
    private val detailCache = HashMap<String, VodDetails>()
    private val categoryCache = HashMap<String, List<VodItem>>()

    fun shelf(kind: VodKind): StateFlow<Shelf> = shelves.getValue(kind).asStateFlow()
    private fun xtream() = source() as? Source.Xtream

    fun load(kind: VodKind, force: Boolean = false) {
        scope.launch {
            locks.getValue(kind).withLock {
                val flow = shelves.getValue(kind)
                if (!force && flow.value.items.isNotEmpty()) return@withLock
                val what = if (kind == VodKind.MOVIE) "movies" else "series"
                val s = xtream() ?: run { flow.value = Shelf(error = "On Demand needs an Xtream login (server, username, password)."); return@withLock }
                flow.value = Shelf(loading = true)
                val cats = runCatching { withContext(Dispatchers.Default) { od.categories(s, kind) } }.getOrElse {
                    flow.value = Shelf(error = "Couldn't load $what: ${it.message ?: "no answer"}"); return@withLock
                }
                flow.value = flow.value.copy(categories = cats)
                flow.value = runCatching {
                    val items = withContext(Dispatchers.Default) { od.items(s, kind) { n -> flow.value = flow.value.copy(count = n) } }
                    Shelf(categories = cats.filter { c -> items.any { it.categoryId == c.id } }.ifEmpty { cats }, items = items)
                }.getOrElse {
                    flow.value.copy(loading = false, error = "Couldn't read the full list of $what; pick a category.")
                }
            }
        }
    }

    suspend fun category(kind: VodKind, id: String): List<VodItem> {
        val shelf = shelves.getValue(kind).value
        if (shelf.items.isNotEmpty()) return shelf.items.filter { it.categoryId == id }
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

    // ---- where you stopped (this device) ----

    data class Progress(val positionMs: Long, val durationMs: Long) {
        val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val finished: Boolean get() = durationMs > 0 && positionMs > durationMs * 0.94
    }
    data class Resume(val item: VodItem, val episodeId: String?, val label: String?, val progress: Progress)

    private val store = NSUserDefaults.standardUserDefaults
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

    private companion object {
        const val KEY_RESUME = "np_vod_resume"
        const val REC = "\u001E"
        const val FLD = "\u001F"
    }
}

object VodApp {
    val library: VodLibrary by lazy { VodLibrary { LiveTvApp.controller.savedSource() } }
}

// ---------------------------------------------------------------- the switch at the top

/** Live TV · Movies · Series. */
@Composable
internal fun ModeTabs(mode: Int, onMode: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(22.dp)).background(LiveColors.Card).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("Live TV", "Movies", "Series").forEachIndexed { i, label ->
            val on = i == mode
            Text(
                label, color = if (on) LiveColors.Background else LiveColors.Text, fontSize = 14.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) LiveColors.Text else Color.Transparent)
                    .clickable { onMode(i) }.padding(horizontal = 16.dp, vertical = 7.dp)
            )
        }
    }
}

// ---------------------------------------------------------------- browsing

private const val CONTINUE = "__continue"
private const val RECENT = "__recent"
private const val ALL = "__all"

private data class Playback(val item: VodItem, val details: VodDetails?, val episode: VodEpisode?, val url: String, val startMs: Long)

@Composable
internal fun OnDemandHome(kind: VodKind, mode: Int, onMode: (Int) -> Unit) {
    val lib = VodApp.library
    val shelf by lib.shelf(kind).collectAsState()
    val resume by lib.resume.collectAsState()
    LaunchedEffect(kind) { lib.load(kind) }
    val continueItems = remember(resume, kind) { resume.filter { it.item.kind == kind } }
    var category by remember(kind) { mutableStateOf(if (continueItems.isNotEmpty()) CONTINUE else ALL) }
    var query by remember(kind) { mutableStateOf("") }
    var opened by remember { mutableStateOf<VodItem?>(null) }
    var playback by remember { mutableStateOf<Playback?>(null) }
    val what = if (kind == VodKind.MOVIE) "movies" else "series"

    val entries = buildList {
        if (continueItems.isNotEmpty()) add(CONTINUE to "Continue watching")
        if (shelf.recent.isNotEmpty()) add(RECENT to "Recently added")
        add(ALL to "All $what")
        shelf.categories.forEach { add(it.id to it.name) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        val grid: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
                VodGrid(lib, kind, shelf, category, query, continueItems, wide) { opened = it }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().statusBarsPadding()) {
                Column(Modifier.width(230.dp).fillMaxHeight().background(LiveColors.Panel).padding(vertical = 16.dp)) {
                    Text(if (kind == VodKind.MOVIE) "Movies" else "Series", color = LiveColors.Text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(entries.size, key = { entries[it].first }) { i ->
                            val (id, name) = entries[i]
                            Text(
                                name, color = if (id == category) LiveColors.Background else LiveColors.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (id == category) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (id == category) LiveColors.Text else Color.Transparent)
                                    .clickable { category = id; query = "" }.padding(horizontal = 10.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModeTabs(mode, onMode)
                        Spacer(Modifier.width(14.dp))
                        Box(Modifier.weight(1f)) { VodSearchField(query, "Search $what") { query = it } }
                    }
                    Spacer(Modifier.height(14.dp))
                    grid(Modifier.weight(1f))
                }
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                ModeTabs(mode, onMode, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(entries.size, key = { entries[it].first }) { i ->
                        val (id, name) = entries[i]
                        val sel = id == category
                        Text(
                            name, color = if (sel) LiveColors.Background else LiveColors.Text, fontSize = 14.sp, maxLines = 1,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (sel) LiveColors.Text else LiveColors.Card)
                                .clickable { category = id; query = "" }.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
                Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { VodSearchField(query, "Search $what") { query = it } }
                grid(Modifier.weight(1f).padding(horizontal = 12.dp))
            }
        }
    }

    opened?.let { item -> VodDetailsSheet(lib, item, onPlay = { playback = it }, onClose = { opened = null }) }
    playback?.let { pb -> VodPlayerScreen(lib, pb, onNext = { playback = it }, onClose = { playback = null }) }
}

@Composable
private fun VodSearchField(query: String, hint: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LiveColors.Card).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(hint, color = LiveColors.Faint, fontSize = 15.sp)
            androidx.compose.foundation.text.BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = LiveColors.Text, fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(LiveColors.Accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) Icon(Icons.Default.Close, "Clear", tint = LiveColors.Faint, modifier = Modifier.size(18.dp).clickable { onChange("") })
    }
}

@Composable
private fun VodGrid(
    lib: VodLibrary, kind: VodKind, shelf: VodLibrary.Shelf, category: String, query: String,
    continueItems: List<VodLibrary.Resume>, wide: Boolean, onOpen: (VodItem) -> Unit
) {
    val full = shelf.items.isNotEmpty()
    val q = OnDemand.normalize(query)
    val list: List<VodItem>? = when {
        q.length >= 2 -> if (!full) null else produceState<List<VodItem>?>(null, q, shelf.items) {
            delay(250)
            value = withContext(Dispatchers.Default) {
                shelf.items.asSequence().filter { OnDemand.normalize(it.title).contains(q) }.take(150).toList()
            }
        }.value
        category == CONTINUE -> continueItems.map { it.item }
        category == RECENT -> if (full) shelf.recent else null
        category == ALL -> if (full) shelf.items else null
        else -> produceState<List<VodItem>?>(null, category, full) {
            value = runCatching { lib.category(kind, category) }.getOrDefault(emptyList())
        }.value
    }
    if (list == null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val err = shelf.error
            if (err != null && !shelf.loading) {
                Text(err, color = LiveColors.Dim, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                PillButton("Try again") { lib.load(kind, force = true) }
            } else {
                Text(if (q.length >= 2) "Search works once the list is loaded…" else "Loading…", color = LiveColors.Dim, fontSize = 15.sp)
                if (shelf.count > 0) Text("${shelf.count} titles so far", color = LiveColors.Faint, fontSize = 13.sp)
                if (shelf.categories.isNotEmpty()) Text("You can already pick a category.", color = LiveColors.Faint, fontSize = 13.sp)
            }
        }
        return
    }
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (q.length >= 2) "Nothing found" else "Nothing here yet", color = LiveColors.Dim, fontSize = 15.sp) }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (wide) 140.dp else 104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(list.size, key = { list[it].key }) { i ->
            val item = list[i]
            val fraction = continueItems.firstOrNull { it.item.key == item.key }?.progress?.fraction
            Column(Modifier.clickable { onOpen(item) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(LiveColors.Card), contentAlignment = Alignment.Center) {
                    Text(item.title, color = LiveColors.Dim, fontSize = 12.sp, maxLines = 4, modifier = Modifier.padding(8.dp))
                    if (!item.poster.isNullOrBlank()) AsyncImage(model = item.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (fraction != null) Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.BottomCenter) { Bar(fraction, Modifier.fillMaxWidth()) }
                }
                Spacer(Modifier.height(5.dp))
                Text(item.title, color = LiveColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.year?.let { Text(it.toString(), color = LiveColors.Faint, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, modifier: Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.Black.copy(alpha = 0.45f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(LiveColors.Accent))
    }
}

private fun timeText(ms: Long): String {
    val s = ms / 1000
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (s >= 3600) "${s / 3600}:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

// ---------------------------------------------------------------- details

@Composable
private fun VodDetailsSheet(lib: VodLibrary, item: VodItem, onPlay: (Playback) -> Unit, onClose: () -> Unit) {
    val details by produceState<VodDetails?>(null, item.key) { value = lib.details(item) }
    var waited by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(item.key) { delay(15_000); waited = true }
    val resume by lib.resume.collectAsState()
    val d = details
    val it2 = d?.item ?: item
    var season by remember(item.key) { mutableStateOf<Int?>(null) }

    Box(Modifier.fillMaxSize().background(LiveColors.Background).pointerInput(Unit) { detectTapGestures { } }) {
        d?.backdrop?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(320.dp)) }
        Box(Modifier.fillMaxWidth().height(320.dp).background(Brush.verticalGradient(listOf(LiveColors.Background.copy(alpha = 0.35f), LiveColors.Background))))
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            IconButton(onClick = onClose) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }
            }
            Spacer(Modifier.height(90.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(LiveColors.Card)) {
                    if (!it2.poster.isNullOrBlank()) AsyncImage(model = it2.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(it2.title, color = LiveColors.Text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val facts = listOfNotNull(
                        it2.year?.toString() ?: d?.releaseDate?.take(4), d?.genre,
                        d?.durationSecs?.takeIf { s -> s > 0 }?.let { s -> if (s >= 3600) "${s / 3600}h ${(s % 3600) / 60}m" else "${s / 60}m" },
                        it2.rating?.let { r -> "★ " + ((r * 10).toInt() / 10.0) },
                        d?.seasons?.size?.takeIf { n -> n > 0 }?.let { n -> if (n == 1) "1 season" else "$n seasons" }
                    ).joinToString("  ·  ")
                    if (facts.isNotBlank()) Text(facts, color = LiveColors.Dim, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (item.kind == VodKind.MOVIE) {
                val p = lib.progress(item.key)
                val url = lib.movieUrl(it2)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (p != null && !p.finished && p.positionMs > 60_000) {
                        PillButton("Resume from ${timeText(p.positionMs)}", icon = Icons.Default.PlayArrow) { url?.let { onPlay(Playback(it2, d, null, it, p.positionMs)) } }
                        PillButton("Start over", primary = false) { url?.let { onPlay(Playback(it2, d, null, it, 0)) } }
                    } else PillButton("Play", icon = Icons.Default.PlayArrow) { url?.let { onPlay(Playback(it2, d, null, it, 0)) } }
                }
                if (resume.any { r -> r.item.key == item.key }) {
                    Spacer(Modifier.height(8.dp))
                    Text("Remove from Continue watching", color = LiveColors.Faint, fontSize = 13.sp, modifier = Modifier.clickable { lib.forget(item) }.padding(vertical = 6.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(d?.plot ?: if (!waited && d == null) "Loading details…" else "No description from the provider.", color = LiveColors.Text, fontSize = 15.sp,
                modifier = Modifier.widthIn(max = 760.dp))
            d?.cast?.let { Spacer(Modifier.height(6.dp)); Text("With $it", color = LiveColors.Faint, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(18.dp))

            if (item.kind == VodKind.SERIES && d != null) {
                if (d.episodes.isEmpty()) Text("The provider didn't list any episodes.", color = LiveColors.Dim, fontSize = 14.sp)
                else {
                    val last = resume.firstOrNull { r -> r.item.key == item.key }?.episodeId
                    val lastEp = d.episodes.firstOrNull { e -> e.id == last }
                    val current = season ?: lastEp?.season ?: d.seasons.first()
                    if (lastEp != null) {
                        val p = lib.progress("e:${lastEp.id}")
                        val next = if (p?.finished == true) d.episodes.getOrNull(d.episodes.indexOf(lastEp) + 1) else lastEp
                        if (next != null) {
                            PillButton("${if (next == lastEp) "Resume" else "Next"}: S${next.season} E${next.episode}", icon = Icons.Default.PlayArrow) {
                                lib.episodeUrl(next)?.let { u -> onPlay(Playback(it2, d, next, u, if (next == lastEp) p?.positionMs ?: 0 else 0)) }
                            }
                            Spacer(Modifier.height(14.dp))
                        }
                    }
                    if (d.seasons.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(d.seasons.size) { i ->
                                val s = d.seasons[i]
                                val on = s == current
                                Text("Season $s", color = if (on) LiveColors.Background else LiveColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) LiveColors.Text else LiveColors.Card)
                                        .clickable { season = s }.padding(horizontal = 14.dp, vertical = 7.dp))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    d.episodes.filter { e -> e.season == current }.forEach { e ->
                        val p = lib.progress("e:${e.id}")
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(LiveColors.Panel)
                                .clickable { lib.episodeUrl(e)?.let { u -> onPlay(Playback(it2, d, e, u, if (p != null && !p.finished) p.positionMs else 0)) } }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${e.episode}", color = LiveColors.Faint, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.title, color = LiveColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sub = listOfNotNull(
                                    e.durationSecs?.takeIf { it > 0 }?.let { "${it / 60} min" },
                                    p?.let { if (it.finished) "Watched" else "Stopped at ${timeText(it.positionMs)}" }
                                ).joinToString(" · ")
                                if (sub.isNotBlank()) Text(sub, color = LiveColors.Faint, fontSize = 12.sp)
                            }
                            if (p != null && !p.finished) Bar(p.fraction, Modifier.width(70.dp))
                        }
                    }
                }
            } else if (item.kind == VodKind.SERIES) {
                Text(if (waited) "Couldn't load the episodes. Go back and try again." else "Loading episodes…", color = LiveColors.Dim, fontSize = 14.sp)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ---------------------------------------------------------------- player

@Composable
private fun VodPlayerScreen(lib: VodLibrary, pb: Playback, onNext: (Playback) -> Unit, onClose: () -> Unit) {
    val player = remember(pb) { LivePlayers.create() }
    if (player == null) { LaunchedEffect(Unit) { onClose() }; return }
    val label = pb.episode?.let { "S${it.season} E${it.episode} · ${it.title}" }
    var status by remember(pb) { mutableStateOf("buffering") }
    var pos by remember(pb) { mutableStateOf(0.0) }
    var dur by remember(pb) { mutableStateOf(0.0) }
    var paused by remember(pb) { mutableStateOf(false) }
    var controls by remember(pb) { mutableStateOf(true) }
    var kick by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var tracks by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save() = lib.save(pb.item, pb.episode?.id, label, (player.position() * 1000).toLong(), (player.duration() * 1000).toLong())

    DisposableEffect(player) {
        LiveTvApp.setFullScreen(true)
        // One stream: Live TV lets go first.
        LiveTvApp.controller.setVisible(false)
        player.playFrom(pb.url, emptyMap(), pb.startMs / 1000.0)
        onDispose {
            if (player.position() > 5) save()
            player.stop(); player.close()
            LiveTvApp.setFullScreen(false)
        }
    }
    LaunchedEffect(player) {
        var ticks = 0
        while (true) {
            delay(500)
            status = player.status(); pos = player.position(); dur = player.duration(); paused = player.isPaused()
            if (++ticks % 20 == 0 && !paused && status == "playing") save()
            if (status == "ended") {
                save()
                val next = pb.details?.episodes?.let { eps -> pb.episode?.let { e -> eps.getOrNull(eps.indexOf(e) + 1) } }
                val u = next?.let { lib.episodeUrl(it) }
                if (next != null && u != null) onNext(pb.copy(episode = next, url = u, startMs = 0)) else onClose()
                break
            }
        }
    }
    LaunchedEffect(controls, kick, paused) { if (controls && !paused && !tracks) { delay(4_000); controls = false } }

    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls; kick++ }) }) {
        PlayerSurface(player, Modifier.fillMaxSize())
        if (status == "buffering") Text("Loading…", color = Color.White, fontSize = 15.sp,
            modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 18.dp, vertical = 8.dp))
        if (status.startsWith("error")) Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(LiveColors.Panel).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("This title couldn't be played. If your subscription is playing on another device, stop it there and try again.", color = LiveColors.Text, fontSize = 15.sp, modifier = Modifier.widthIn(max = 420.dp))
            Spacer(Modifier.height(12.dp))
            PillButton("Try again") { player.playFrom(pb.url, emptyMap(), pos) }
        }
        if (controls || paused) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, "Close", tint = Color.White)
                    }
                }
                IconButton(onClick = { tracks = true; kick++ }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Subtitles, "Sound and subtitles", tint = Color.White)
                    }
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    BigIcon(Icons.Default.Replay10, "Back 10 seconds", 52) { player.seek(-10.0, true); kick++ }
                    BigIcon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "Play" else "Pause", 72) { player.setPaused(!paused); paused = !paused; kick++ }
                    BigIcon(Icons.Default.Forward30, "Forward 30 seconds", 52) { player.seek(30.0, true); kick++ }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp)) {
                    Text(pb.item.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    label?.let { Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1) }
                    val shown = dragging ?: if (dur > 0) (pos / dur).toFloat() else 0f
                    Slider(
                        value = shown.coerceIn(0f, 1f),
                        onValueChange = { dragging = it; kick++ },
                        onValueChangeFinished = { dragging?.let { f -> if (dur > 0) player.seek(f * dur, false) }; dragging = null },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = LiveColors.Accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
                    )
                    Row {
                        Text(timeText(((dragging?.let { it * dur } ?: pos) * 1000).toLong()), color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        if (dur > 0) Text("-" + timeText(((dur - pos) * 1000).toLong()), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                    }
                }
            }
        }
        if (tracks) TrackSheet(player, onClose = { tracks = false; kick++ })
    }
}

@Composable
private fun BigIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: Int, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 2).dp)).background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size((size * 0.55).dp))
    }
}

@Composable
private fun TrackSheet(player: LivePlayer, onClose: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    fun parse(type: String) = player.tracks(type).split('\n').filter { it.isNotBlank() }.mapNotNull {
        val f = it.split('\t'); if (f.size < 3) null else Triple(f[0], f[1], f[2] == "1")
    }
    val audio = remember(tick) { parse("audio") }
    val subs = remember(tick) { parse("sub") }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }, contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 640.dp).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(LiveColors.Panel)
                .pointerInput(Unit) { detectTapGestures { } }.padding(20.dp)
        ) {
            Text("SOUND", color = LiveColors.Faint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (audio.isEmpty()) Text("Only one sound track", color = LiveColors.Dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            audio.forEach { (id, name, on) -> TrackRow(name, on) { player.selectTrack("audio", id); tick++ } }
            Spacer(Modifier.height(14.dp))
            Text("SUBTITLES", color = LiveColors.Faint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            TrackRow("Off", subs.none { it.third }) { player.selectTrack("sub", "no"); tick++ }
            subs.forEach { (id, name, on) -> TrackRow(name, on) { player.selectTrack("sub", id); tick++ } }
            Spacer(Modifier.height(10.dp))
            PillButton("Done") { onClose() }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TrackRow(name: String, on: Boolean, onClick: () -> Unit) {
    Text(
        (if (on) "✓  " else "     ") + name, color = if (on) LiveColors.Accent else LiveColors.Text, fontSize = 15.sp,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)
    )
}
