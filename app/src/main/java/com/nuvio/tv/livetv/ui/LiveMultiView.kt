package com.nuvio.tv.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val MAX_TILES = 4

/**
 * Nuvio Plus: multi-view. Up to four channels at once: two side by side, three or four in a
 * 2×2 grid. The highlighted tile's sound plays (the others are muted). OK: that channel full
 * screen. Hold OK: swap or remove it. The empty "+" tile adds a channel from the group on screen.
 * Back: leaves multi-view on the channel you were listening to.
 *
 * Each tile is its own small player, so the provider has to allow that many streams at once
 * (most accounts allow 1–2; a tile that won't start says so).
 */
@Composable
internal fun LiveMultiView(
    start: List<LiveChannel>,
    pickFrom: List<LiveChannel>,
    playback: LiveTvPlaybackController,
    onFullScreen: (LiveChannel) -> Unit,
    onClose: (LiveChannel?) -> Unit
) {
    val tiles = remember { mutableStateListOf<LiveChannel>().apply { addAll(start.take(MAX_TILES)) } }
    val players = remember { mutableStateMapOf<String, ExoPlayer>() }
    val errors = remember { mutableStateMapOf<String, String>() }
    var focused by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    /** Picking a channel: for a new tile (-1) or to swap tile n. */
    var pickingFor by remember { mutableStateOf<Int?>(null) }
    val focusers = remember { List(MAX_TILES) { FocusRequester() } }

    // The main player steps aside (one stream less for the provider), and every tile's player
    // is let go when multi-view closes.
    DisposableEffect(Unit) {
        playback.stop()
        onDispose {
            players.values.forEach { runCatching { it.release() } }
            players.clear()
        }
    }

    // A player per channel on screen; dropped ones released.
    val keys = tiles.map { it.key }
    LaunchedEffect(keys) {
        (players.keys - keys.toSet()).forEach { k -> players.remove(k)?.let { runCatching { it.release() } }; errors.remove(k) }
        tiles.forEach { ch ->
            if (ch.key !in players) {
                val p = runCatching { playback.multiViewPlayer(ch) }.getOrNull()
                if (p == null) { errors[ch.key] = "Couldn't start this channel"; return@forEach }
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        errors[ch.key] = "Not playing: the provider may allow fewer streams at once"
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) errors.remove(ch.key)
                    }
                })
                players[ch.key] = p
            }
        }
    }
    // Sound from the highlighted tile only.
    LaunchedEffect(focused, keys, players.size) {
        tiles.forEachIndexed { i, ch -> players[ch.key]?.volume = if (i == focused) 1f else 0f }
    }
    // A tile that stopped with an error: try again every 15 s.
    LaunchedEffect(keys) {
        while (true) {
            delay(15_000)
            tiles.forEach { ch ->
                val p = players[ch.key] ?: return@forEach
                if (p.playerError != null) runCatching { p.prepare(); p.playWhenReady = true }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { focusers[0].requestFocus() } }

    BackHandler(enabled = menuFor == null && pickingFor == null) { onClose(tiles.getOrNull(focused)) }

    val slots = if (tiles.size < MAX_TILES) tiles.size + 1 else tiles.size // + the "add" tile
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp)
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = 22.dp)) {
            val gap = 14.dp
            val cols = if (slots <= 1) 1 else 2
            val rowCount = if (slots <= 2) 1 else 2
            val byWidth = (maxWidth - gap * (cols - 1)) / cols
            val byHeight = (maxHeight - gap * (rowCount - 1)) / rowCount * (16f / 9f)
            val tileW = if (byWidth < byHeight) byWidth else byHeight
            val tileSize = Modifier.size(tileW, tileW * (9f / 16f))
            val rows: List<List<Int>> = if (slots <= 2) listOf((0 until slots).toList()) else listOf(listOf(0, 1), (2 until slots).toList())
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { i ->
                            if (i < tiles.size) {
                                Tile(
                                    modifier = tileSize,
                                    channel = tiles[i],
                                    player = players[tiles[i].key],
                                    error = errors[tiles[i].key],
                                    hasSound = i == focused,
                                    focusRequester = focusers[i],
                                    onFocus = { focused = i },
                                    onClick = { onFullScreen(tiles[i]) },
                                    onLongClick = { menuFor = i }
                                )
                            } else {
                                AddTile(tileSize, focusers[i], onFocus = { focused = i }) { pickingFor = -1 }
                            }
                        }
                    }
                }
            }
        }
        LiveText(
            "Multi-view · sound from the highlighted channel · OK: full screen · hold OK: swap or remove · Back: leave",
            modifier = Modifier.align(Alignment.BottomCenter),
            color = Color.White.copy(alpha = 0.55f),
            size = 12.sp
        )
    }

    menuFor?.let { i ->
        val ch = tiles.getOrNull(i)
        if (ch == null) { menuFor = null; return@let }
        LiveDialog(onDismiss = { menuFor = null }, width = 380.dp) {
            LiveText(ch.name, size = 18.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            MenuItem("Watch full screen") { menuFor = null; onFullScreen(ch) }
            MenuItem("Swap for another channel…") { menuFor = null; pickingFor = i }
            if (tiles.size > 1) MenuItem("Remove from multi-view") {
                menuFor = null
                tiles.removeAt(i)
                focused = focused.coerceAtMost(tiles.size - 1).coerceAtLeast(0)
            }
        }
    }

    pickingFor?.let { slot ->
        val onScreen = tiles.map { it.key }.toSet()
        val choices = pickFrom.filter { it.key !in onScreen }
        LiveDialog(onDismiss = { pickingFor = null }, width = 460.dp) {
            LiveText(if (slot < 0) "Add a channel" else "Swap for…", size = 18.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (choices.isEmpty()) LiveText("No other channels in this group.", color = NuvioTheme.colors.TextSecondary, size = 14.sp)
            val first = remember { FocusRequester() }
            LazyColumn(Modifier.height(420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(choices.take(400), key = { it.key }) { ch ->
                    MenuItem(
                        "${ch.number}  ${ch.name}",
                        if (ch.key == choices.first().key) Modifier.focusRequester(first) else Modifier
                    ) {
                        if (slot < 0) { if (tiles.size < MAX_TILES) tiles.add(ch); focused = tiles.size - 1 }
                        else if (slot < tiles.size) tiles[slot] = ch
                        pickingFor = null
                    }
                }
            }
            LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    modifier: Modifier,
    channel: LiveChannel,
    player: ExoPlayer?,
    error: String?,
    hasSound: Boolean,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .clip(shape)
            .background(Color(0xFF10151D))
            .border(if (isFocused) 4.dp else 1.dp, if (isFocused) Color(0xFFF2F4F8) else Color.White.copy(alpha = 0.12f), shape)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused; if (it.isFocused) onFocus() }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        if (player != null) LivePlayerSurface(player = player, useSurfaceView = false, modifier = Modifier.fillMaxSize().padding(if (isFocused) 4.dp else 1.dp))
        if (error != null) LiveText(
            error,
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
            color = Color.White.copy(alpha = 0.8f),
            size = 14.sp
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xCC0A0D12))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiveText("${channel.number}  ${channel.name}", color = Color.White, size = 14.sp, weight = FontWeight.SemiBold)
            if (hasSound) {
                Spacer(Modifier.padding(start = 8.dp))
                LiveText("♪", color = NOWSEEN_ACCENT, size = 14.sp, weight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AddTile(modifier: Modifier, focusRequester: FocusRequester, onFocus: () -> Unit, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (isFocused) Color(0xFFF2F4F8) else Color(0xFF10151D))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused; if (it.isFocused) onFocus() }
            .combinedClickableCompat(onClick),
        contentAlignment = Alignment.Center
    ) {
        LiveText("+  Add a channel", color = if (isFocused) Color(0xFF0A0D12) else Color.White.copy(alpha = 0.7f), size = 18.sp, weight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
