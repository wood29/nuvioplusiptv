package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.data.CatchupUrlBuilder
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * Nuvio Plus mini guide (mockup 13, Sky style): opened from "Later" in full screen.
 * Five channels around the playing one, each with Now / Next / Later cards, and the highlighted
 * show's picture and description on top.
 *  - Up / Down: channel. Left / Right: earlier / later shows on that channel.
 *  - OK: on what's on now, watch it; on an earlier show with catch-up, play it from the archive;
 *    on a later show, set or cancel a reminder.
 *  - Back: close.
 */
@Composable
internal fun NpMiniGuide(viewModel: LiveTvViewModel, onClose: () -> Unit) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val user by viewModel.userState.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val use24h = settings.use24HourClock
    val colors = NuvioTheme.colors
    val accent = colors.Secondary

    val channels = ui.channels
    var chIndex by remember { mutableIntStateOf(channels.indexOfFirst { it.key == playback.channelKey }.coerceAtLeast(0)) }
    // 0 = on now, 1 = next, 2 = later, ... (negative = earlier shows).
    var slot by remember { mutableIntStateOf(0) }
    var activity by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }
    LaunchedEffect(activity) { delay(30_000); onClose() }

    fun showsFrom(ch: LiveChannel?, from: Int, count: Int): List<EpgProgram?> {
        val list = ch?.let { programs[it.key] }.orEmpty()
        val nowAt = list.indexOfFirst { now < it.stopMs }.let { if (it < 0) list.size else it }
        return (0 until count).map { list.getOrNull(nowAt + from + it) }
    }
    val focusedChannel = channels.getOrNull(chIndex)
    LaunchedEffect(focusedChannel?.key) { focusedChannel?.let { viewModel.ensureChannelSchedule(it.key) } }
    // The window of three cards follows the highlight: Now / Next / Later, then it slides on.
    val firstSlot = (slot - 2).coerceAtLeast(0).coerceAtMost(slot).let { if (slot < 0) slot else it }
    val focused: EpgProgram? = showsFrom(focusedChannel, slot, 1).firstOrNull()

    fun activate() {
        val ch = focusedChannel ?: return
        val p = focused
        when {
            p == null || (now >= p.startMs && now < p.stopMs) -> { if (ch.key != playback.channelKey) viewModel.preview(ch); onClose() }
            p.stopMs <= now && CatchupUrlBuilder.isAvailable(ch.catchup, p.startMs, now) -> { if (viewModel.playCatchup(ch, p)) onClose() }
            p.startMs > now -> viewModel.toggleReminder(ch, p)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent isOk || e.key == Key.Back
                activity++
                when {
                    e.key == Key.DirectionUp -> if (channels.isNotEmpty()) chIndex = (chIndex - 1).let { if (it < 0) channels.lastIndex else it }
                    e.key == Key.DirectionDown -> if (channels.isNotEmpty()) chIndex = (chIndex + 1).let { if (it > channels.lastIndex) 0 else it }
                    e.key == Key.ChannelUp || e.key == Key.PageUp -> chIndex = (chIndex - 5).coerceAtLeast(0)
                    e.key == Key.ChannelDown || e.key == Key.PageDown -> chIndex = (chIndex + 5).coerceAtMost((channels.size - 1).coerceAtLeast(0))
                    e.key == Key.DirectionLeft -> {
                        val canEarlier = focusedChannel?.let { CatchupUrlBuilder.isAvailable(it.catchup, now - 3_600_000L, now) } == true
                        slot = (slot - 1).coerceAtLeast(if (canEarlier) -6 else 0)
                    }
                    e.key == Key.DirectionRight -> if (showsFrom(focusedChannel, slot + 1, 1).firstOrNull() != null) slot++
                    isOk -> activate()
                    e.key == Key.Back -> onClose()
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusable()
    ) {
        // Dim the video, darkest at the bottom where the cards are.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.5f to Color.Black.copy(alpha = 0.75f), 1f to Color.Black.copy(alpha = 0.95f))))

        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
            // ---- the highlighted show
            Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                val art = focused?.icon
                if (!art.isNullOrBlank()) {
                    coil3.compose.AsyncImage(
                        model = art, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(300.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LiveText(
                        listOfNotNull(
                            focusedChannel?.let { "${it.number}  ${it.name}" },
                            focused?.let { formatDayClock(it.startMs, now, use24h) + " – " + formatClock(it.stopMs, use24h) }
                        ).joinToString("  ·  "),
                        color = Color.White.copy(alpha = 0.75f), size = 14.sp
                    )
                    LiveText(focused?.title ?: (focusedChannel?.name ?: ""), color = Color.White, size = 28.sp, weight = FontWeight.ExtraBold, maxLines = 1)
                    val meta = listOfNotNull(
                        focused?.episode?.takeIf { settings.showEpisodeNumbers && it.isNotBlank() }, focused?.category, focused?.year?.toString()
                    )
                    if (meta.isNotEmpty()) LiveText(meta.joinToString("  ·  "), color = Color.White.copy(alpha = 0.85f), size = 14.sp)
                    ScrollingDescription(focused?.description ?: "No description available.", color = Color.White.copy(alpha = 0.8f), size = 14.sp, maxLines = 3)
                    val hint = when {
                        focused == null -> "OK  Watch"
                        now >= focused.startMs && now < focused.stopMs -> "OK  Watch"
                        focused.startMs > now -> if (user.reminders.any { it.channelKey == focusedChannel?.key && it.startMs == focused.startMs }) "OK  Cancel reminder" else "OK  Remind me"
                        else -> "OK  Play from archive"
                    }
                    LiveText("$hint     ◀ ▶  Earlier / later     ▲ ▼  Channel", color = accent, size = 13.sp, weight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.weight(1f))
            // ---- column headings
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Spacer(Modifier.width(220.dp))
                (0 until 3).forEach { i ->
                    val s = firstSlot + i
                    val label = when {
                        s == 0 -> "Now"
                        s == 1 -> "Next"
                        s < 0 -> "Earlier"
                        else -> "Later"
                    }
                    LiveText(label, color = Color.White.copy(alpha = 0.7f), size = 13.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 12.dp))
                }
            }
            // ---- five channels around the highlighted one
            val rows = (-2..2).map { d -> if (channels.isEmpty()) null else channels.getOrNull(((chIndex + d) % channels.size + channels.size) % channels.size) }
                .let { if (channels.size < 5) channels.map { it } else it.filterNotNull() }
            rows.forEach { ch ->
                val isRow = ch.key == focusedChannel?.key
                Row(
                    Modifier.fillMaxWidth().height(62.dp).padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier.width(212.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                            .background(if (isRow) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.05f))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (!ch.logo.isNullOrBlank()) ChannelLogo(ch.logo, 30.dp)
                        Column {
                            LiveText(ch.name, color = Color.White, size = 14.sp, weight = FontWeight.Bold, maxLines = 1)
                            LiveText(ch.number.toString() + if (ch.key == playback.channelKey) "  · playing" else "", color = Color.White.copy(alpha = 0.6f), size = 11.sp)
                        }
                    }
                    showsFrom(ch, firstSlot, 3).forEachIndexed { i, p ->
                        val sel = isRow && firstSlot + i == slot
                        val live = p != null && now >= p.startMs && now < p.stopMs
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                                .background(if (sel) Color.White else Color.White.copy(alpha = if (isRow) 0.1f else 0.05f))
                                .then(if (sel) Modifier else if (live && isRow) Modifier.border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp)) else Modifier)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            val fg = if (sel) Color(0xFF0A0D12) else Color.White
                            LiveText(p?.title ?: "No information", color = fg, size = 14.sp, weight = FontWeight.Bold, maxLines = 1)
                            if (p != null) {
                                val left = if (live) "  ·  ${((p.stopMs - now) / 60_000L).coerceAtLeast(1)} min left" else ""
                                LiveText(formatClock(p.startMs, use24h) + " – " + formatClock(p.stopMs, use24h) + left, color = fg.copy(alpha = 0.7f), size = 11.sp, maxLines = 1)
                                if (live) {
                                    val f = ((now - p.startMs).toFloat() / (p.stopMs - p.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                                    Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(fg.copy(alpha = 0.2f))) {
                                        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(if (sel) Color(0xFF0A0D12) else accent))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
