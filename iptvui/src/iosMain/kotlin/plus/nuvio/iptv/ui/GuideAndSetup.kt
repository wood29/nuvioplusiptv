/*
 * Nuvio Plus Live TV screens: written from scratch (see iptvui/LICENSE).
 */
package plus.nuvio.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970
import plus.nuvio.iptv.Channel
import plus.nuvio.iptv.Guide
import plus.nuvio.iptv.Programme
import plus.nuvio.iptv.Source

// ---------------------------------------------------------------- times

private val clockFormat = NSDateFormatter().apply { setLocalizedDateFormatFromTemplate("jj:mm") }
private val dayFormat = NSDateFormatter().apply { setLocalizedDateFormatFromTemplate("EEE jj:mm") }

internal fun clock(ms: Long): String = clockFormat.stringFromDate(NSDate.dateWithTimeIntervalSince1970(ms / 1000.0))

/** "20:15" today, "Fri 20:15" on other days. */
internal fun dayClock(ms: Long, now: Long): String {
    val sameDay = ms / DAY_MS == now / DAY_MS
    return (if (sameDay) clockFormat else dayFormat).stringFromDate(NSDate.dateWithTimeIntervalSince1970(ms / 1000.0))
}

private const val DAY_MS = 86_400_000L
private const val HALF_HOUR = 1_800_000L

// ---------------------------------------------------------------- the time grid

/**
 * The full TV guide: channels down the left, three hours across (scroll for more), now
 * marked with a line. Tap a channel to watch it, a show for its details.
 */
@Composable
internal fun GuideGrid(
    channels: List<Channel>, guide: Guide, now: Long, playing: Channel?,
    onClose: () -> Unit, onChannel: (Channel) -> Unit, onShow: (Channel, Programme) -> Unit
) {
    val start = now - now % HALF_HOUR
    val hours = 12
    val end = start + hours * 3_600_000L
    val perMinute = 4.2f          // dp per minute
    val hScroll = rememberScrollState()
    val nameWidth = 130.dp
    Box(Modifier.fillMaxSize().background(LiveColors.Background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("TV Guide", color = LiveColors.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = LiveColors.Text) }
            }
            // time ruler
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(nameWidth))
                Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                    Box(Modifier.width((((end - start) / 60_000L) * perMinute).dp).height(26.dp)) {
                        var t = start
                        while (t < end) {
                            Text(clock(t), color = LiveColors.Dim, fontSize = 12.sp,
                                modifier = Modifier.offset(x = (((t - start) / 60_000L) * perMinute).dp).padding(start = 4.dp))
                            t += HALF_HOUR
                        }
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(channels, key = { it.id }) { ch ->
                    Row(Modifier.fillMaxWidth().height(64.dp).padding(vertical = 2.dp)) {
                        Row(
                            Modifier.width(nameWidth).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                                .background(if (ch.id == playing?.id) LiveColors.Card else LiveColors.Panel)
                                .clickable { onChannel(ch) }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Logo(ch, 32.dp)
                            Text(ch.name, color = LiveColors.Text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                            Box(Modifier.width((((end - start) / 60_000L) * perMinute).dp).fillMaxHeight()) {
                                guide.between(ch, start, end).forEach { p ->
                                    val from = maxOf(p.startMs, start)
                                    val to = minOf(p.stopMs, end)
                                    val x = (((from - start) / 60_000L) * perMinute).dp
                                    val w = (((to - from) / 60_000L) * perMinute).dp
                                    val live = p.isOnAt(now)
                                    Column(
                                        Modifier.offset(x = x).width(w).fillMaxHeight().padding(start = 2.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (live) LiveColors.Card else LiveColors.Panel)
                                            .then(if (live) Modifier.border(1.dp, LiveColors.Accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp)) else Modifier)
                                            .clickable { onShow(ch, p) }.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(p.title, color = if (p.stopMs <= now) LiveColors.Faint else LiveColors.Text, fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(clock(p.startMs), color = LiveColors.Faint, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                                // now
                                Box(Modifier.offset(x = (((now - start) / 60_000L) * perMinute).dp).width(2.dp).fillMaxHeight().background(LiveColors.Live))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- sign-in

@Composable
internal fun SetupScreen(c: LiveTvController) {
    val state by c.state.collectAsState()
    var xtream by remember { mutableStateOf(true) }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var guideUrl by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Live TV", color = LiveColors.Text, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                Text("Add your IPTV provider. Nuvio Plus doesn't include any channels: it plays yours.", color = LiveColors.Dim, fontSize = 15.sp)
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(LiveColors.Card).padding(4.dp)) {
                    Segment("Xtream login", xtream) { xtream = true }
                    Segment("M3U link", !xtream) { xtream = false }
                }
                if (xtream) {
                    Field("Server (e.g. http://example.com:8080)", server, KeyboardType.Uri) { server = it }
                    Field("Username", user) { user = it }
                    Field("Password", pass, secret = true) { pass = it }
                } else {
                    Field("Playlist link (M3U)", url, KeyboardType.Uri) { url = it }
                    Field("TV guide link (XMLTV, optional)", guideUrl, KeyboardType.Uri) { guideUrl = it }
                }
                state.error?.let { Text(it, color = LiveColors.Live, fontSize = 14.sp) }
                val ready = if (xtream) server.isNotBlank() && user.isNotBlank() && pass.isNotBlank() else url.isNotBlank()
                Box(Modifier.padding(top = 6.dp)) {
                    PillButton(if (state.loading) "Connecting…" else "Connect") {
                        if (!ready || state.loading) return@PillButton
                        c.connect(
                            if (xtream) Source.Xtream("main", "My provider", server.trim(), user.trim(), pass)
                            else Source.M3u("main", "My playlist", url.trim(), guideUrl.trim().ifBlank { null })
                        )
                    }
                }
                Text("Your login stays on this device.", color = LiveColors.Faint, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(text, color = if (selected) LiveColors.Background else LiveColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(if (selected) LiveColors.Text else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, secret: Boolean = false, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = LiveColors.Dim, fontSize = 13.sp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = LiveColors.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(LiveColors.Accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else type, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LiveColors.Card).padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}
