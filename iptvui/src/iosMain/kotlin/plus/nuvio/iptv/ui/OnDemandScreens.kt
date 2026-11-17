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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.SkipNext
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
import plus.nuvio.iptv.TmdbInfo
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

// ---------------------------------------------------------------- the library

/** Movies and series from the provider, and where you stopped watching. One for the app. */
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

private const val HOME = "__home"
private const val MYLIST = "__mylist"
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
    val myList by lib.myList.collectAsState()
    val mine = remember(myList, kind) { myList.filter { it.kind == kind } }
    var category by remember(kind) { mutableStateOf(HOME) }
    var manage by remember { mutableStateOf(false) }
    var tmdbSheet by remember { mutableStateOf(false) }
    var query by remember(kind) { mutableStateOf("") }
    var opened by remember { mutableStateOf<VodItem?>(null) }
    var playback by remember { mutableStateOf<Playback?>(null) }
    val what = if (kind == VodKind.MOVIE) "movies" else "series"

    val entries = buildList {
        add(HOME to "Home")
        if (continueItems.isNotEmpty()) add(CONTINUE to "Continue watching")
        if (mine.isNotEmpty()) add(MYLIST to "My list")
        if (shelf.recent.isNotEmpty()) add(RECENT to "Recently added")
        add(ALL to "All $what")
        shelf.categories.forEach { add(it.id to it.name) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        val grid: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
                if (category == HOME && query.length < 2) VodHomeRows(lib, kind, shelf, continueItems, mine, wide, onOpen = { opened = it }, onPick = { category = it })
                else VodGrid(lib, kind, shelf, category, query, continueItems, mine, wide) { opened = it }
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
                    SideLink("Hide categories…") { manage = true }
                    SideLink("Movie info (TMDB)…") { tmdbSheet = true }
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
                    item(key = "__manage") {
                        Text("Hide categories", color = LiveColors.Dim, fontSize = 14.sp,
                            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(LiveColors.Card).clickable { manage = true }.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                    item(key = "__tmdb") {
                        Text("Movie info", color = LiveColors.Dim, fontSize = 14.sp,
                            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(LiveColors.Card).clickable { tmdbSheet = true }.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
                Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { VodSearchField(query, "Search $what") { query = it } }
                grid(Modifier.weight(1f).padding(horizontal = 12.dp))
            }
        }
    }

    opened?.let { item -> VodDetailsSheet(lib, item, onPlay = { playback = it }, onOpen = { opened = it }, onClose = { opened = null }) }
    if (manage) CategoryManagerSheet(lib, kind, onClose = { manage = false })
    if (tmdbSheet) TmdbSheet(lib, onClose = { tmdbSheet = false })
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
    continueItems: List<VodLibrary.Resume>, mine: List<VodItem>, wide: Boolean, onOpen: (VodItem) -> Unit
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
        category == MYLIST -> mine
        category == HOME -> if (full) shelf.items else null
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
                val note = if (category == CONTINUE) continueItems.firstOrNull { it.item.key == item.key }?.let { leftNote(it) } else null
                (note ?: item.year?.toString())?.let { Text(it, color = LiveColors.Faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** "S2 E4 · 23 min left" for a Continue card. */
private fun leftNote(r: VodLibrary.Resume): String {
    val left = ((r.progress.durationMs - r.progress.positionMs) / 60_000).coerceAtLeast(1)
    val ep = r.label?.substringBefore(" · ")
    return listOfNotNull(ep, "$left min left").joinToString(" · ")
}

@Composable
private fun SideLink(text: String, onClick: () -> Unit) {
    Text(text, color = LiveColors.Dim, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp))
}

// ---------------------------------------------------------------- the Home page

@Composable
private fun VodHomeRows(
    lib: VodLibrary, kind: VodKind, shelf: VodLibrary.Shelf, continueItems: List<VodLibrary.Resume>, mine: List<VodItem>, wide: Boolean,
    onOpen: (VodItem) -> Unit, onPick: (String) -> Unit
) {
    val rows = shelf.rows
    if (rows == null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val err = shelf.error
            if (err != null && !shelf.loading) {
                Text(err, color = LiveColors.Dim, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                PillButton("Try again") { lib.load(kind, force = true) }
            } else {
                Text("Loading ${if (kind == VodKind.MOVIE) "movies" else "series"}…", color = LiveColors.Dim, fontSize = 15.sp)
                if (shelf.count > 0) Text("${shelf.count} titles so far", color = LiveColors.Faint, fontSize = 13.sp)
            }
        }
        return
    }
    // "Because you watched": TMDB's suggestions for the last thing you watched, when a key is set.
    val last = continueItems.firstOrNull()?.item ?: mine.firstOrNull()
    val because by produceState<List<VodItem>>(emptyList(), last?.key, shelf.items.size) {
        value = if (last == null || lib.tmdbKey == null) emptyList()
        else runCatching { lib.extra(last, null)?.let { lib.related(it, last) }.orEmpty() }.getOrDefault(emptyList())
    }
    val hero = rows.top.firstOrNull() ?: rows.recent.firstOrNull()
    val cardW = if (wide) 132.dp else 104.dp
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        hero?.let { h ->
            item(key = "hero") {
                Box(Modifier.fillMaxWidth().height(if (wide) 260.dp else 190.dp).clip(RoundedCornerShape(16.dp)).background(LiveColors.Card).clickable { onOpen(h) }) {
                    if (!h.poster.isNullOrBlank()) AsyncImage(model = h.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(LiveColors.Background.copy(alpha = 0.92f), Color.Transparent))))
                    Column(Modifier.align(Alignment.BottomStart).padding(18.dp).widthIn(max = 420.dp)) {
                        Text(if (rows.top.isNotEmpty()) "TOP PICK" else "NEW", color = LiveColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(h.title, color = LiveColors.Text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val facts = listOfNotNull(h.year?.toString(), h.rating?.let { "★ " + ((it * 10).toInt() / 10.0) }).joinToString("  ·  ")
                        if (facts.isNotBlank()) Text(facts, color = LiveColors.Dim, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        PillButton("Details", icon = Icons.Default.PlayArrow) { onOpen(h) }
                    }
                }
            }
        }
        if (continueItems.isNotEmpty()) item(key = "cont") {
            HomeRow("Continue watching", continueItems.map { it.item }, cardW, notes = continueItems.associate { it.item.key to leftNote(it) },
                progress = continueItems.associate { it.item.key to it.progress.fraction }, onMore = { onPick(CONTINUE) }, onOpen = onOpen)
        }
        if (mine.isNotEmpty()) item(key = "mine") { HomeRow("My list", mine, cardW, onMore = { onPick(MYLIST) }, onOpen = onOpen) }
        if (because.isNotEmpty() && last != null) item(key = "because") { HomeRow("Because you watched ${last.title}", because, cardW, onOpen = onOpen) }
        if (rows.top.isNotEmpty()) item(key = "top") { TopTenRow(rows.top, if (wide) 120.dp else 96.dp, onOpen) }
        if (rows.recent.isNotEmpty()) item(key = "recent") { HomeRow("Recently added", rows.recent, cardW, onMore = { onPick(RECENT) }, onOpen = onOpen) }
        if (rows.family.isNotEmpty()) item(key = "family") { HomeRow("Family time", rows.family, cardW, onOpen = onOpen) }
        if (rows.rated.isNotEmpty()) item(key = "rated") { HomeRow("Highly rated", rows.rated, cardW, onOpen = onOpen) }
        rows.biggest.forEachIndexed { i, (name, list) ->
            item(key = "big$i") {
                val id = shelf.categories.firstOrNull { it.name == name }?.id
                HomeRow(name, list, cardW, onMore = id?.let { { onPick(it) } }, onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun HomeRow(
    title: String, items: List<VodItem>, cardW: androidx.compose.ui.unit.Dp,
    notes: Map<String, String> = emptyMap(), progress: Map<String, Float> = emptyMap(),
    onMore: (() -> Unit)? = null, onOpen: (VodItem) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = LiveColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onMore != null) Text("See all", color = LiveColors.Accent, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onMore).padding(6.dp))
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items.size, key = { items[it].key }) { i ->
                val item = items[i]
                Column(Modifier.width(cardW).clickable { onOpen(item) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(LiveColors.Card), contentAlignment = Alignment.Center) {
                        Text(item.title, color = LiveColors.Dim, fontSize = 12.sp, maxLines = 4, modifier = Modifier.padding(8.dp))
                        if (!item.poster.isNullOrBlank()) AsyncImage(model = item.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        progress[item.key]?.let { f -> Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.BottomCenter) { Bar(f, Modifier.fillMaxWidth()) } }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(item.title, color = LiveColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    (notes[item.key] ?: item.year?.toString())?.let { Text(it, color = LiveColors.Faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/** Top 10 with big outlined numbers beside the posters. */
@Composable
private fun TopTenRow(items: List<VodItem>, cardW: androidx.compose.ui.unit.Dp, onOpen: (VodItem) -> Unit) {
    Column {
        Text("Top 10", color = LiveColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items.size, key = { items[it].key }) { i ->
                val item = items[i]
                Row(Modifier.clickable { onOpen(item) }, verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${i + 1}", fontSize = 72.sp, fontWeight = FontWeight.Black, color = LiveColors.Dim,
                        style = androidx.compose.ui.text.TextStyle(
                            drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                        ),
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    Box(Modifier.width(cardW).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(LiveColors.Card), contentAlignment = Alignment.Center) {
                        Text(item.title, color = LiveColors.Dim, fontSize = 12.sp, maxLines = 4, modifier = Modifier.padding(8.dp))
                        if (!item.poster.isNullOrBlank()) AsyncImage(model = item.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
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
private fun VodDetailsSheet(lib: VodLibrary, item: VodItem, onPlay: (Playback) -> Unit, onOpen: (VodItem) -> Unit, onClose: () -> Unit) {
    val details by produceState<VodDetails?>(null, item.key) { value = lib.details(item) }
    // Optional extras from TMDB, only with the user's own key.
    var loadedDetails by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(details) { if (details != null) loadedDetails = true }
    val extra by produceState<TmdbInfo?>(null, item.key, loadedDetails) {
        if (lib.tmdbKey == null) return@produceState
        if (!loadedDetails) delay(4_000)
        value = lib.extra(item, details)
    }
    val related by produceState<List<VodItem>>(emptyList(), extra) { value = extra?.let { lib.related(it, item) }.orEmpty() }
    val myList by lib.myList.collectAsState()
    val inList = myList.any { it.key == item.key }
    // Back from a "More like this" title returns here.
    val history = remember { mutableListOf<VodItem>() }
    var waited by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(item.key) { delay(15_000); waited = true }
    val resume by lib.resume.collectAsState()
    val d = details
    val it2 = d?.item ?: item
    var season by remember(item.key) { mutableStateOf<Int?>(null) }

    Box(Modifier.fillMaxSize().background(LiveColors.Background).pointerInput(Unit) { detectTapGestures { } }) {
        (d?.backdrop ?: extra?.backdrop)?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(320.dp)) }
        Box(Modifier.fillMaxWidth().height(320.dp).background(Brush.verticalGradient(listOf(LiveColors.Background.copy(alpha = 0.35f), LiveColors.Background))))
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            IconButton(onClick = { history.removeLastOrNull()?.let(onOpen) ?: onClose() }) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }
            }
            Spacer(Modifier.height(90.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(LiveColors.Card)) {
                    (it2.poster?.takeIf { p -> p.isNotBlank() } ?: extra?.poster)?.let { p -> AsyncImage(model = p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(it2.title, color = LiveColors.Text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val facts = listOfNotNull(
                        it2.year?.toString() ?: d?.releaseDate?.take(4) ?: extra?.year?.toString(),
                        d?.genre ?: extra?.genres?.take(3)?.joinToString(", ")?.ifBlank { null },
                        d?.durationSecs?.takeIf { s -> s > 0 }?.let { s -> if (s >= 3600) "${s / 3600}h ${(s % 3600) / 60}m" else "${s / 60}m" }
                            ?: extra?.runtimeMin?.takeIf { m -> m > 0 }?.let { m -> if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" },
                        (it2.rating ?: extra?.rating)?.let { r -> "★ " + ((r * 10).toInt() / 10.0) },
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
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(if (inList) "In my list" else "My list", primary = false, icon = if (inList) Icons.Default.Check else Icons.Default.Add) { lib.toggleMyList(it2) }
                (d?.trailerYouTubeId ?: extra?.trailerYouTubeId)?.takeIf { t -> t.isNotBlank() }?.let { t ->
                    PillButton("Trailer", primary = false, icon = Icons.Default.PlayArrow) {
                        NSURL.URLWithString("https://www.youtube.com/watch?v=$t")?.let { u -> UIApplication.sharedApplication.openURL(u, emptyMap<Any?, Any?>(), null) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(d?.plot?.takeIf { t -> t.isNotBlank() } ?: extra?.overview ?: if (!waited && d == null) "Loading details…" else "No description from the provider.", color = LiveColors.Text, fontSize = 15.sp,
                modifier = Modifier.widthIn(max = 760.dp))
            val people = extra?.cast.orEmpty()
            if (people.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("Cast", color = LiveColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(people.size) { i ->
                        val c = people[i]
                        Column(Modifier.width(78.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)).background(LiveColors.Card), contentAlignment = Alignment.Center) {
                                Text(c.name.split(' ').mapNotNull { w -> w.firstOrNull()?.toString() }.take(2).joinToString(""), color = LiveColors.Dim, fontSize = 16.sp)
                                c.photo?.let { ph -> AsyncImage(model = ph, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            }
                            Text(c.name, color = LiveColors.Text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            c.character?.let { ch -> Text(ch, color = LiveColors.Faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            } else d?.cast?.let { Spacer(Modifier.height(6.dp)); Text("With $it", color = LiveColors.Faint, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
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
            if (related.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                HomeRow("More like this", related, 104.dp) { r -> history.add(item); onOpen(r) }
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
    fun save() = lib.save(pb.item, pb.episode?.id, label, (player.position() * 1000).toLong(), (player.duration() * 1000).toLong())
    var episodes by remember { mutableStateOf(false) }
    var skipNext by remember(pb) { mutableStateOf(false) }
    val nextEp = remember(pb) { pb.details?.episodes?.let { eps -> pb.episode?.let { e -> eps.getOrNull(eps.indexOf(e) + 1) } } }
    fun playNext() { val n = nextEp ?: return; lib.episodeUrl(n)?.let { u -> save(); onNext(pb.copy(episode = n, url = u, startMs = 0)) } }
    val scope = rememberCoroutineScope()

    DisposableEffect(player) {
        LiveTvApp.setFullScreen(true)
        // One stream: Live TV lets go first.
        LiveTvApp.controller.setVisible(false)
        player.playFrom(pb.url, emptyMap(), pb.startMs / 1000.0)
        SubtitleLook.apply(player)
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
                val u = nextEp?.let { lib.episodeUrl(it) }
                if (nextEp != null && u != null && !skipNext) onNext(pb.copy(episode = nextEp, url = u, startMs = 0)) else onClose()
                break
            }
        }
    }
    LaunchedEffect(controls, kick, paused) { if (controls && !paused && !tracks && !episodes) { delay(4_000); controls = false } }

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
                Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                    if (pb.episode != null && !pb.details?.episodes.isNullOrEmpty()) IconButton(onClick = { episodes = true; kick++ }) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.List, "Episodes", tint = Color.White)
                        }
                    }
                    IconButton(onClick = { tracks = true; kick++ }) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Subtitles, "Sound and subtitles", tint = Color.White)
                        }
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
        // Next episode in the last 30 seconds.
        val left = dur - pos
        if (nextEp != null && !skipNext && dur > 120 && left in 0.5..30.0 && status == "playing") {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(24.dp).clip(RoundedCornerShape(16.dp)).background(LiveColors.Panel.copy(alpha = 0.95f)).padding(16.dp).widthIn(max = 320.dp)
            ) {
                Text("Next episode in ${left.toInt()} s", color = LiveColors.Faint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("S${nextEp.season} E${nextEp.episode} · ${nextEp.title}", color = LiveColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Play now", icon = Icons.Default.SkipNext) { playNext() }
                    PillButton("Cancel", primary = false) { skipNext = true }
                }
            }
        }
        if (tracks) TrackSheet(player, onClose = { tracks = false; kick++ })
        if (episodes) pb.details?.let { d ->
            EpisodeSheet(lib, d, pb.episode, onPick = { e -> lib.episodeUrl(e)?.let { u -> save(); episodes = false; onNext(pb.copy(episode = e, url = u, startMs = 0)) } }, onClose = { episodes = false; kick++ })
        }
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
            if (subs.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("SUBTITLE SIZE", color = LiveColors.Faint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    listOf("Small" to 0.8, "Normal" to 1.0, "Large" to 1.3, "Huge" to 1.6).forEach { (n, v) ->
                        val on = SubtitleLook.scale == v
                        Text(n, color = if (on) LiveColors.Background else LiveColors.Text, fontSize = 14.sp,
                            modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) LiveColors.Text else LiveColors.Card)
                                .clickable { SubtitleLook.scale = v; SubtitleLook.apply(player); tick++ }.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                Text("SUBTITLE TIMING", color = LiveColors.Faint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    PillButton("− 0.5 s", primary = false) { SubtitleLook.delay -= 0.5; SubtitleLook.apply(player); tick++ }
                    Text((if (SubtitleLook.delay > 0) "+" else "") + SubtitleLook.delay + " s", color = LiveColors.Text, fontSize = 15.sp)
                    PillButton("+ 0.5 s", primary = false) { SubtitleLook.delay += 0.5; SubtitleLook.apply(player); tick++ }
                    if (SubtitleLook.delay != 0.0) Text("Reset", color = LiveColors.Accent, fontSize = 13.sp, modifier = Modifier.clickable { SubtitleLook.delay = 0.0; SubtitleLook.apply(player); tick++ })
                }
            }
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

/** Subtitle size (kept) and timing (this title only). */
private object SubtitleLook {
    var scale: Double
        get() = NSUserDefaults.standardUserDefaults.doubleForKey("np_sub_scale").takeIf { it > 0 } ?: 1.0
        set(v) = NSUserDefaults.standardUserDefaults.setDouble(v, "np_sub_scale")
    var delay = 0.0
    fun apply(player: LivePlayer) {
        player.setOption("sub-scale", scale.toString())
        player.setOption("sub-delay", delay.toString())
    }
}

@Composable
private fun EpisodeSheet(lib: VodLibrary, d: VodDetails, current: VodEpisode?, onPick: (VodEpisode) -> Unit, onClose: () -> Unit) {
    var season by remember { mutableStateOf(current?.season ?: d.seasons.firstOrNull() ?: 1) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }, contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.fillMaxHeight().width(360.dp).background(LiveColors.Panel).pointerInput(Unit) { detectTapGestures { } }.statusBarsPadding().padding(16.dp)
        ) {
            Text("Episodes", color = LiveColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (d.seasons.size > 1) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(d.seasons.size) { i ->
                        val s = d.seasons[i]; val on = s == season
                        Text("Season $s", color = if (on) LiveColors.Background else LiveColors.Text, fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) LiveColors.Text else LiveColors.Card).clickable { season = s }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val eps = d.episodes.filter { it.season == season }
            LazyColumn(Modifier.weight(1f)) {
                items(eps.size, key = { eps[it].id }) { i ->
                    val e = eps[i]
                    val p = lib.progress("e:${e.id}")
                    val now = e.id == current?.id
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(if (now) LiveColors.Card else Color.Transparent)
                            .clickable { if (!now) onPick(e) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${e.episode}", color = if (now) LiveColors.Accent else LiveColors.Faint, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, color = LiveColors.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = if (now) "Playing" else p?.let { if (it.finished) "Watched" else "Stopped at ${timeText(it.positionMs)}" }
                            sub?.let { Text(it, color = LiveColors.Faint, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- hiding categories

@Composable
private fun CategoryManagerSheet(lib: VodLibrary, kind: VodKind, onClose: () -> Unit) {
    val all = remember { lib.allCategories(kind) }
    val counts = remember { lib.counts(kind) }
    var hidden by remember { mutableStateOf(lib.hidden(kind)) }
    val byTag = remember(all) { all.groupBy { OnDemand.categoryTag(it.name) }.filterKeys { it != null }.mapKeys { it.key!! }.toList().sortedByDescending { it.second.size } }
    fun set(ids: Set<String>) { hidden = ids; lib.setHidden(kind, ids) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }, contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 720.dp).fillMaxHeight(0.85f).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(LiveColors.Panel)
                .pointerInput(Unit) { detectTapGestures { } }.padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show or hide categories", color = LiveColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                PillButton("Done") { onClose() }
            }
            Text("Hidden ones are left out of Home, Top 10, search and More like this.", color = LiveColors.Dim, fontSize = 13.sp)
            if (all.isEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("The list is still loading. Try again in a moment.", color = LiveColors.Dim, fontSize = 14.sp)
                return@Column
            }
            if (byTag.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("BY LANGUAGE (tap to hide or show all)", color = LiveColors.Faint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(byTag.size, key = { byTag[it].first }) { i ->
                        val (tag, cats) = byTag[i]
                        val ids = cats.map { it.id }.toSet()
                        val off = hidden.containsAll(ids)
                        Text((if (off) "✕ " else "") + "$tag · ${cats.size}", color = if (off) LiveColors.Faint else LiveColors.Background, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (off) LiveColors.Card else LiveColors.Text)
                                .clickable { set(if (off) hidden - ids else hidden + ids) }.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Show all", color = LiveColors.Accent, fontSize = 13.sp, modifier = Modifier.clickable { set(emptySet()) }.padding(vertical = 6.dp))
                Text("Hide all", color = LiveColors.Accent, fontSize = 13.sp, modifier = Modifier.clickable { set(all.map { it.id }.toSet()) }.padding(vertical = 6.dp))
                Text("${hidden.size} hidden", color = LiveColors.Faint, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            LazyColumn(Modifier.weight(1f)) {
                items(all.size, key = { all[it].id }) { i ->
                    val c = all[i]
                    val on = c.id !in hidden
                    Row(Modifier.fillMaxWidth().clickable { set(if (on) hidden + c.id else hidden - c.id) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) LiveColors.Accent else LiveColors.Card), contentAlignment = Alignment.Center) {
                            if (on) Icon(Icons.Default.Check, null, tint = LiveColors.Background, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, color = if (on) LiveColors.Text else LiveColors.Faint, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        counts[c.id]?.let { Text("$it", color = LiveColors.Faint, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- TMDB key

@Composable
private fun TmdbSheet(lib: VodLibrary, onClose: () -> Unit) {
    var key by remember { mutableStateOf(lib.tmdbKey.orEmpty()) }
    var note by remember { mutableStateOf<String?>(if (lib.tmdbKey != null) "A key is saved." else null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).padding(20.dp).clip(RoundedCornerShape(20.dp)).background(LiveColors.Panel).pointerInput(Unit) { detectTapGestures { } }.padding(22.dp)
        ) {
            Text("Movie info (optional)", color = LiveColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("With your own free TMDB key, details pages get descriptions, cast photos, trailers and \"More like this\". Get one at themoviedb.org › Settings › API.",
                color = LiveColors.Dim, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            VodSearchField(key, "TMDB API key or read access token") { key = it.trim() }
            note?.let { Spacer(Modifier.height(8.dp)); Text(it, color = LiveColors.Faint, fontSize = 13.sp) }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(if (busy) "Checking…" else "Check & save") {
                    if (busy || key.isBlank()) return@PillButton
                    busy = true
                    scope.launch {
                        val ok = runCatching { lib.checkTmdb(key) }.getOrDefault(false)
                        busy = false
                        if (ok) { lib.tmdbKey = key; note = "Saved. Details pages now show more." } else note = "That key didn't work. Check it and try again."
                    }
                }
                if (lib.tmdbKey != null) PillButton("Remove", primary = false) { lib.tmdbKey = null; key = ""; note = "Removed." }
                PillButton("Close", primary = false) { onClose() }
            }
        }
    }
}
