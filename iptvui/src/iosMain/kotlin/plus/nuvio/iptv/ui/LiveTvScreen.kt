/*
 * Nuvio Plus Live TV screens: written from scratch (see iptvui/LICENSE).
 *
 * Layout after Fjord Player:
 *  - iPhone: the live picture on top, the groups as chips, search, then the channel list with
 *    what's on now (and a progress bar) and next.
 *  - iPad (wide): groups in a sidebar on the left; the picture above the channel list on the
 *    right; "Guide" opens the time grid.
 * Tap the picture for full screen (with channel up / down); tap a show for its details.
 */
package plus.nuvio.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import plus.nuvio.iptv.Channel
import plus.nuvio.iptv.Group
import plus.nuvio.iptv.Programme

/** The Nuvio+ colours (from the logo): navy, teal "+", blue. Same as the TV box's Nuvio+ theme. */
internal object LiveColors {
    val Background = Color(0xFF030C24)
    val Panel = Color(0xFF0B1733)
    val Card = Color(0xFF12203F)
    val Line = Color(0xFF1E2C4D)
    val Text = Color(0xFFF2F6FF)
    val Dim = Color(0xFFA7B3CC)
    val Faint = Color(0xFF7A88A6)
    val Accent = Color(0xFF00F5C3)
    val Blue = Color(0xFF1E6BFF)
    val Live = Color(0xFFE5484D)
}

/** The Live TV tab. Shows the sign-in until a provider is set up. */
@Composable
fun LiveTvTab(controller: LiveTvController, modifier: Modifier = Modifier) {
    val hasSource by controller.hasSource.collectAsState()
    Box(modifier.fillMaxSize().background(LiveColors.Background)) {
        if (!hasSource) SetupScreen(controller) else LiveTvHome(controller)
    }
}

@Composable
private fun LiveTvHome(c: LiveTvController) {
    val state by c.state.collectAsState()
    val group by c.group.collectAsState()
    val query by c.query.collectAsState()
    val playing by c.playing.collectAsState()
    val favs by c.favourites.collectAsState()
    val now by c.clock.collectAsState()
    var fullScreen by remember { mutableStateOf(false) }
    var guideOpen by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<Pair<Channel, Programme>?>(null) }

    val lineup = state.lineup
    if (lineup == null) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (state.loading) "Loading your channels…" else state.error ?: "No channels", color = LiveColors.Text, fontSize = 17.sp)
            if (state.error != null) {
                Spacer(Modifier.height(16.dp))
                PillButton("Try again") { c.retry() }
                Spacer(Modifier.height(8.dp))
                PillButton("Change provider", primary = false) { c.signOut() }
            }
        }
        return
    }
    val channels = remember(lineup, group, query, favs) { c.visible(lineup.channels, group, query, favs) }
    val guide = state.guide
    // Pick up where you left off (or the first channel) once the lineup is there.
    LaunchedEffect(lineup) { if (c.player == null) (playing ?: lineup.channels.firstOrNull())?.let { c.play(it) } }

    val player = c.player
    val onChannel: (Channel) -> Unit = { ch -> if (ch.id == playing?.id) fullScreen = true else c.play(ch) }
    val onShow: (Channel, Programme) -> Unit = { ch, p -> details = ch to p }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        if (wide) {
            Row(Modifier.fillMaxSize().statusBarsPadding()) {
                GroupSidebar(lineup.groups, group, favs.isNotEmpty(), onPick = { c.pickGroup(it); c.search("") }, onSignOut = { c.signOut() })
                Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.weight(1.4f)) {
                            PlayerBox(if (fullScreen) null else player, playing, playing?.let { guide.nowNext(it, now).now }, now) { fullScreen = true }
                        }
                        Column(Modifier.weight(1f)) {
                            NowPanel(playing, playing?.let { guide.nowNext(it, now) }, now, onShow)
                            Spacer(Modifier.height(12.dp))
                            PillButton("TV Guide", icon = Icons.Default.GridView) { guideOpen = true }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    SearchField(query) { c.search(it) }
                    Spacer(Modifier.height(10.dp))
                    ChannelList(channels, playing, favs, guide, now, onChannel, onShow, { c.toggleFavourite(it) })
                }
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                PlayerBox(if (fullScreen) null else player, playing, playing?.let { guide.nowNext(it, now).now }, now, Modifier.padding(horizontal = 12.dp)) { fullScreen = true }
                Spacer(Modifier.height(10.dp))
                GroupChips(lineup.groups, group, favs.isNotEmpty()) { c.pickGroup(it); c.search("") }
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SearchField(query) { c.search(it) } }
                    IconButton(onClick = { guideOpen = true }) { Icon(Icons.Default.GridView, "TV Guide", tint = LiveColors.Text) }
                    IconButton(onClick = { c.signOut() }) { Icon(Icons.Default.Logout, "Change provider", tint = LiveColors.Faint) }
                }
                ChannelList(channels, playing, favs, guide, now, onChannel, onShow, { c.toggleFavourite(it) }, Modifier.padding(horizontal = 12.dp))
            }
        }
        if (state.guideLoading) {
            Text("Loading the TV guide…", color = LiveColors.Dim, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).clip(RoundedCornerShape(20.dp)).background(LiveColors.Card).padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }

    if (guideOpen) GuideGrid(channels, guide, now, playing, onClose = { guideOpen = false }, onChannel = { c.play(it); guideOpen = false }, onShow = onShow)
    if (fullScreen && player != null && playing != null) {
        val zapList = channels.ifEmpty { lineup.channels }
        FullScreenPlayer(player, playing!!, zapList, guide, now,
            onClose = { fullScreen = false }, onZap = { c.zap(it, zapList) }, onPick = { c.play(it) })
    }
    details?.let { (ch, p) ->
        ProgrammeSheet(ch, p, now, guide.alsoShown(p.title, now, lineup.channels).filterNot { it.second.startMs == p.startMs && it.first.id == ch.id },
            canArchive = p.stopMs <= now && c.engine.catchupUrl(ch, p) != null,
            onWatch = { if (p.isOnAt(now)) c.play(ch) else c.playArchive(ch, p); details = null },
            onClose = { details = null })
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun PlayerBox(player: LivePlayer?, playing: Channel?, now: Programme?, nowMs: Long, modifier: Modifier = Modifier, onTap: () -> Unit) {
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black).clickable(onClick = onTap)) {
        if (player != null) PlayerSurface(player, Modifier.fillMaxSize())
        else Text("The player isn't available", color = LiveColors.Dim, modifier = Modifier.align(Alignment.Center))
        if (playing != null) {
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LiveBadge()
                Text(playing.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                now?.let { Text("· ${it.title}", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun NowPanel(playing: Channel?, nn: plus.nuvio.iptv.NowNext?, now: Long, onShow: (Channel, Programme) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LiveColors.Panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(playing?.name ?: "Pick a channel", color = LiveColors.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
        val list = listOfNotNull(nn?.now?.let { "Now" to it }, nn?.next?.let { "Next" to it }, nn?.later?.let { "Later" to it })
        if (list.isEmpty()) Text("No guide information", color = LiveColors.Dim, fontSize = 13.sp)
        list.forEach { (label, p) ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { playing?.let { onShow(it, p) } }.padding(4.dp)) {
                Text("$label  ·  ${clock(p.startMs)} – ${clock(p.stopMs)}", color = if (label == "Now") LiveColors.Accent else LiveColors.Faint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(p.title, color = LiveColors.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (label == "Now") Progress(p, now, Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun GroupSidebar(groups: List<Group>, picked: String?, hasFavs: Boolean, onPick: (String) -> Unit, onSignOut: () -> Unit) {
    Column(Modifier.width(230.dp).fillMaxHeight().background(LiveColors.Panel).padding(vertical = 16.dp)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tv, null, tint = LiveColors.Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Live TV", color = LiveColors.Text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            item { SidebarRow("All channels", picked == LiveTvController.FAVOURITES_OFF || picked == null) { onPick(LiveTvController.FAVOURITES_OFF) } }
            if (hasFavs) item { SidebarRow("★  Favourites", picked == LiveTvController.FAVOURITES) { onPick(LiveTvController.FAVOURITES) } }
            items(groups, key = { it.id }) { g -> SidebarRow(g.name, picked == g.id) { onPick(g.id) } }
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = onSignOut).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Logout, null, tint = LiveColors.Faint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Change provider", color = LiveColors.Faint, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SidebarRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) LiveColors.Background else LiveColors.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) LiveColors.Text else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp)
    )
}

@Composable
private fun GroupChips(groups: List<Group>, picked: String?, hasFavs: Boolean, onPick: (String) -> Unit) {
    val all = buildList {
        add(LiveTvController.FAVOURITES_OFF to "All")
        if (hasFavs) add(LiveTvController.FAVOURITES to "★ Favourites")
        groups.forEach { add(it.id to it.name) }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
        items(all, key = { it.first }) { (id, name) ->
            val sel = picked == id || (picked == null && id == LiveTvController.FAVOURITES_OFF)
            Text(
                name, color = if (sel) LiveColors.Background else LiveColors.Text, fontSize = 14.sp, maxLines = 1,
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (sel) LiveColors.Text else LiveColors.Card)
                    .clickable { onPick(id) }.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LiveColors.Card).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, tint = LiveColors.Faint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search channels", color = LiveColors.Faint, fontSize = 15.sp)
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
private fun ChannelList(
    channels: List<Channel>, playing: Channel?, favs: Set<String>, guide: plus.nuvio.iptv.Guide, now: Long,
    onChannel: (Channel) -> Unit, onShow: (Channel, Programme) -> Unit, onFav: (Channel) -> Unit, modifier: Modifier = Modifier
) {
    if (channels.isEmpty()) {
        Text("No channels here", color = LiveColors.Dim, modifier = modifier.padding(24.dp))
        return
    }
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(channels, key = { it.id }) { ch ->
            val nn = guide.nowNext(ch, now)
            val isPlaying = ch.id == playing?.id
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (isPlaying) LiveColors.Card else LiveColors.Panel)
                    .then(if (isPlaying) Modifier.border(1.dp, LiveColors.Accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp)) else Modifier)
                    .clickable { onChannel(ch) }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(ch.number.toString(), color = LiveColors.Faint, fontSize = 12.sp, modifier = Modifier.width(30.dp))
                Logo(ch, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(ch.name, color = LiveColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val p = nn.now
                    if (p != null) {
                        Text(p.title, color = LiveColors.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onShow(ch, p) })
                        Progress(p, now, Modifier.padding(vertical = 3.dp))
                    }
                    nn.next?.let { n ->
                        Text("${clock(n.startMs)}  ${n.title}", color = LiveColors.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onShow(ch, n) })
                    }
                }
                Icon(
                    if (ch.id in favs) Icons.Default.Star else Icons.Default.StarBorder, "Favourite",
                    tint = if (ch.id in favs) LiveColors.Accent else LiveColors.Faint,
                    modifier = Modifier.size(22.dp).clickable { onFav(ch) }
                )
            }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
internal fun Logo(ch: Channel, size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(LiveColors.Card), contentAlignment = Alignment.Center) {
        if (!ch.logo.isNullOrBlank()) AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
        else Text(ch.name.take(2).uppercase(), color = LiveColors.Dim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun Progress(p: Programme, now: Long, modifier: Modifier = Modifier) {
    val f = ((now - p.startMs).toFloat() / (p.stopMs - p.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(LiveColors.Line)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(LiveColors.Accent))
    }
}

@Composable
internal fun LiveBadge() {
    Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(LiveColors.Live).padding(horizontal = 5.dp, vertical = 1.dp))
}

@Composable
internal fun PillButton(text: String, primary: Boolean = true, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).background(if (primary) LiveColors.Text else LiveColors.Card).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { Icon(icon, null, tint = if (primary) LiveColors.Background else LiveColors.Text, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = if (primary) LiveColors.Background else LiveColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

// ---------------------------------------------------------------- full screen

/**
 * Full screen, by touch:
 *  - tap: show or hide the banner (channel, what's on, next) and the close button
 *  - swipe up / down: next / previous channel
 *  - swipe left: the channel list slides in on the right (tap one to switch); swipe right closes it
 * The app's tab bar is hidden while this is open.
 */
@Composable
private fun FullScreenPlayer(
    player: LivePlayer, ch: Channel, channels: List<Channel>, guide: plus.nuvio.iptv.Guide, now: Long,
    onClose: () -> Unit, onZap: (Int) -> Unit, onPick: (Channel) -> Unit
) {
    val nn = guide.nowNext(ch, now)
    var banner by remember { mutableStateOf(true) }
    var bannerKick by remember { mutableStateOf(0) }
    var list by remember { mutableStateOf(false) }
    LaunchedEffect(banner, bannerKick, ch.id) { if (banner && !list) { kotlinx.coroutines.delay(5_000); banner = false } }
    LaunchedEffect(ch.id) { banner = true; bannerKick++ }

    androidx.compose.runtime.DisposableEffect(Unit) {
        LiveTvApp.setFullScreen(true)
        onDispose { LiveTvApp.setFullScreen(false) }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    if (list) list = false else { banner = !banner; bannerKick++ }
                })
            }
            .pointerInput(channels.size) {
                var dx = 0f; var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        val min = 60.dp.toPx()
                        if (kotlin.math.abs(dy) > kotlin.math.abs(dx) && kotlin.math.abs(dy) > min) {
                            if (!list) onZap(if (dy < 0) 1 else -1)
                        } else if (kotlin.math.abs(dx) > min) {
                            list = dx < 0
                            if (list) banner = false
                        }
                    }
                ) { change, drag -> change.consume(); dx += drag.x; dy += drag.y }
            }
    ) {
        PlayerSurface(player, Modifier.fillMaxSize())

        androidx.compose.animation.AnimatedVisibility(
            banner && !list, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, "Close", tint = Color.White)
                    }
                }
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Logo(ch, 60.dp)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LiveBadge(); Text("${ch.number}  ${ch.name}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                        }
                        nn.now?.let {
                            Text(it.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(clock(it.startMs), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                                Progress(it, now, Modifier.width(320.dp))
                                Text(clock(it.stopMs), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                            }
                        }
                        nn.next?.let { Text("Next  ${clock(it.startMs)}  ${it.title}", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1) }
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Swipe ↑ ↓  channels", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        Text("Swipe ←  channel list", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            list,
            enter = androidx.compose.animation.slideInHorizontally { it } + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally { it } + androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            val state = androidx.compose.foundation.lazy.rememberLazyListState(
                initialFirstVisibleItemIndex = (channels.indexOfFirst { it.id == ch.id } - 2).coerceAtLeast(0)
            )
            LazyColumn(
                state = state,
                modifier = Modifier.width(380.dp).fillMaxHeight().background(LiveColors.Background.copy(alpha = 0.92f))
                    .statusBarsPadding().padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)
            ) {
                items(channels, key = { it.id }) { c2 ->
                    val on = c2.id == ch.id
                    val p = guide.nowNext(c2, now).now
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (on) LiveColors.Text else LiveColors.Panel)
                            .clickable { onPick(c2); list = false }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(c2.number.toString(), color = if (on) LiveColors.Background else LiveColors.Faint, fontSize = 12.sp, modifier = Modifier.width(44.dp), maxLines = 1)
                        Logo(c2, 40.dp)
                        Column(Modifier.weight(1f)) {
                            Text(c2.name, color = if (on) LiveColors.Background else LiveColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            p?.let { Text(it.title, color = if (on) LiveColors.Background.copy(alpha = 0.7f) else LiveColors.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = 0.18f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

// ---------------------------------------------------------------- programme details

@Composable
private fun ProgrammeSheet(
    ch: Channel, p: Programme, now: Long, also: List<Pair<Channel, Programme>>, canArchive: Boolean,
    onWatch: () -> Unit, onClose: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(LiveColors.Panel).clickable(enabled = false) {}.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!p.icon.isNullOrBlank()) {
                AsyncImage(p.icon, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Logo(ch, 28.dp)
                Text("${ch.name}  ·  ${dayClock(p.startMs, now)} – ${clock(p.stopMs)}", color = LiveColors.Dim, fontSize = 13.sp)
                if (p.isOnAt(now)) LiveBadge()
            }
            Text(p.title, color = LiveColors.Text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            val meta = listOfNotNull(p.episode, p.category, p.year?.toString(), "${p.durationMinutes} min")
            Text(meta.joinToString("  ·  "), color = LiveColors.Text.copy(alpha = 0.85f), fontSize = 13.sp)
            p.description?.let { Text(it, color = LiveColors.Dim, fontSize = 14.sp, maxLines = 8, overflow = TextOverflow.Ellipsis) }
            if (p.people.isNotEmpty()) Text("With " + p.people.take(6).joinToString(", "), color = LiveColors.Faint, fontSize = 13.sp, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                when {
                    p.isOnAt(now) -> PillButton("Watch", onClick = onWatch)
                    canArchive -> PillButton("Play from archive", onClick = onWatch)
                }
                PillButton("Close", primary = false, onClick = onClose)
            }
            if (also.isNotEmpty()) {
                Text("Also shown", color = LiveColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    also.forEach { (c2, p2) ->
                        Column(Modifier.width(190.dp).clip(RoundedCornerShape(12.dp)).background(LiveColors.Card).padding(10.dp)) {
                            Text(dayClock(p2.startMs, now), color = LiveColors.Dim, fontSize = 12.sp)
                            Text(p2.episode ?: p2.title, color = LiveColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(c2.name, color = LiveColors.Faint, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
