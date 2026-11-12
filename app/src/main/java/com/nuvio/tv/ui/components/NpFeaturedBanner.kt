package com.nuvio.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Nuvio Plus: the featured banner at the top of Home (and On Demand), Apple TV style: a few
 * titles one after another, full width, each with its wide backdrop, logo, a line of details,
 * a short description and buttons. It moves on by itself every 9 s while you leave it alone;
 * Left on the first button / Right on the last one flip through it. Down hides it and goes to
 * the rows (Continue Watching); Up from the top row brings it back.
 *
 * [content] is the page underneath (Home's rows); the banner lies over it while shown.
 */
@Composable
fun NpFeaturedBannerHost(
    items: List<MetaPreview>,
    /** The row the page has the highlight on (to tell "Up at the top row" from a normal Up). */
    focusedRowKey: () -> String?,
    /** Live TV's row (tucked above the top row) has the highlight. */
    liveRowFocused: () -> Boolean = { false },
    label: String,
    onOpen: (MetaPreview) -> Unit,
    onWatchlist: ((MetaPreview) -> Unit)? = null,
    inWatchlist: (MetaPreview) -> Boolean = { false },
    onShown: (MetaPreview) -> Unit = {},
    /** False: Up from the banner goes on to buttons above it (On Demand's Movies / Series). */
    keepUp: Boolean = true,
    content: @Composable () -> Unit
) {
    var showing by rememberSaveable { mutableStateOf(true) }
    androidx.compose.runtime.DisposableEffect(showing) {
        NpBannerState.showing.value = showing
        onDispose { NpBannerState.showing.value = false }
    }
    val scope = rememberCoroutineScope()
    val contentFocus = remember { FocusRequester() }
    val active = showing && items.isNotEmpty()
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (!active && items.isNotEmpty() && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
                    // Up that doesn't move the highlight to another row: it was the top row.
                    val before = focusedRowKey()
                    val liveBefore = liveRowFocused()
                    scope.launch {
                        delay(160)
                        if (focusedRowKey() == before && (!liveRowFocused() || liveBefore)) showing = true
                    }
                }
                false
            }
    ) {
        Box(Modifier.fillMaxSize().focusRequester(contentFocus).focusGroup()) { content() }
        AnimatedVisibility(
            visible = active,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            NpFeaturedBanner(
                items = items,
                label = label,
                onOpen = onOpen,
                onWatchlist = onWatchlist,
                inWatchlist = inWatchlist,
                onShown = onShown,
                keepUp = keepUp,
                onDown = {
                    showing = false
                    scope.launch {
                        // The rows take the highlight back where it was.
                        repeat(4) {
                            delay(60)
                            if (runCatching { contentFocus.requestFocus() }.isSuccess) return@launch
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun NpFeaturedBanner(
    items: List<MetaPreview>,
    label: String,
    onOpen: (MetaPreview) -> Unit,
    onWatchlist: ((MetaPreview) -> Unit)?,
    inWatchlist: (MetaPreview) -> Boolean,
    onShown: (MetaPreview) -> Unit,
    keepUp: Boolean,
    onDown: () -> Unit
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    if (index >= items.size) index = 0
    var lastKeyAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val first = remember { FocusRequester() }
    val item = items[index]
    LaunchedEffect(item.id) { onShown(item) }
    // Moves on by itself while you leave the remote alone.
    LaunchedEffect(items.size) {
        while (items.size > 1) {
            delay(1_000)
            if (System.currentTimeMillis() - lastKeyAt >= 9_000) {
                index = (index + 1) % items.size
                lastKeyAt = System.currentTimeMillis()
            }
        }
    }
    // Take the highlight (the page underneath may try to take it as it opens).
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(8) {
            delay(150)
            // Not while the menu is open (it took the highlight away from the menu).
            if (!hasFocus && !NpBannerState.menuOpen) runCatching { first.requestFocus() }
        }
    }
    // The menu hands the highlight back here when it closes while the banner shows.
    androidx.compose.runtime.DisposableEffect(Unit) {
        NpBannerState.focusBanner = { runCatching { first.requestFocus() }.isSuccess }
        onDispose { NpBannerState.focusBanner = null }
    }
    fun step(d: Int) {
        index = (index + d + items.size) % items.size
        lastKeyAt = System.currentTimeMillis()
    }
    // The top part of the screen only (Apple TV style): the first row (Continue Watching) stays
    // in view below it.
    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(BANNER_HEIGHT)
            .background(Color(0xFF0A0D12))
            .onFocusChanged { hasFocus = it.hasFocus }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastKeyAt = System.currentTimeMillis()
                when (e.key) {
                    Key.DirectionDown -> { onDown(); true }
                    // Top-bar menu: Up from the banner goes into the bar.
                    Key.DirectionUp -> if (NpBannerState.onUp?.invoke() == true) true else keepUp
                    else -> false
                }
            }
    ) {
        Crossfade(targetState = item, animationSpec = tween(450), label = "npBanner") { m ->
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = m.background ?: m.landscapePoster,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter
                )
                // Dark on the left (the text) and at the bottom (the hint and dots).
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            0f to Color(0xF20A0D12), 0.42f to Color(0xB30A0D12), 0.7f to Color(0x000A0D12)
                        )
                    )
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.5f to Color(0x000A0D12), 1f to Color(0xFF0A0D12))
                    )
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 56.dp, bottom = 22.dp)
                .widthIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // With the top-bar menu the bar sits where this line would be.
            if (!NpBannerState.topBarOn.collectAsState().value) {
                Text(label.uppercase(), color = com.nuvio.tv.ui.theme.NuvioTheme.colors.Secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
            if (!item.logo.isNullOrBlank()) {
                AsyncImage(
                    model = item.logo,
                    contentDescription = item.name,
                    modifier = Modifier.heightIn(max = 70.dp).widthIn(max = 320.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart
                )
            } else {
                Text(item.name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val meta = listOfNotNull(
                item.releaseInfo?.take(4)?.takeIf { it.isNotBlank() },
                item.genres.take(2).joinToString(" · ").takeIf { it.isNotBlank() },
                item.imdbRating?.takeIf { it > 0f }?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) }
            ).joinToString("  ·  ")
            if (meta.isNotBlank()) Text(meta, color = Color(0xFFC9D0DC), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            item.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color(0xFFB7BFCE), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val buttons = buildList<Pair<String, () -> Unit>> {
                    // Opens the title's page, where Play is the first button.
                    add("▶  Watch" to { onOpen(item) })
                    if (onWatchlist != null) add((if (inWatchlist(item)) "✓  In Watchlist" else "+  Watchlist") to { onWatchlist(item) })
                }
                buttons.forEachIndexed { i, (text, action) ->
                    BannerButton(
                        text = text,
                        modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                // Right on the last button shows the next title, one per press (holding
                                // it raced through them). Left on the first button goes to the menu.
                                when {
                                    i == buttons.lastIndex && e.key == Key.DirectionRight -> {
                                        if (e.nativeKeyEvent.repeatCount == 0) step(1)
                                        true
                                    }
                                    else -> false
                                }
                            },
                        onClick = action
                    )
                }
            }
            if (items.size > 1) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(width = if (i == index) 22.dp else 7.dp, height = 7.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (i == index) Color.White else Color.White.copy(alpha = 0.3f))
                    )
                }
            }
        }
    }
}

@Composable
private fun BannerButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color(0xFFF2F4F8) else Color.White.copy(alpha = 0.14f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        color = if (focused) Color(0xFF0A0D12) else Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold
    )
}

/** How much of the screen's height the banner takes (the rows start just below). */
private const val BANNER_HEIGHT = 0.56f


/** Nuvio Plus: whether a big banner is showing at the top (the top-bar menu shows over it). */
object NpBannerState {
    val showing = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Set when the top bar was just turned on in Settings: it opens once so you see it. */
    val openTopBar = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** The top-bar menu is in use. */
    val topBarOn = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Set by the top-bar menu: Up from the banner opens it (returns true when it did). */
    @Volatile var onUp: (() -> Boolean)? = null
    /** Set while the banner shows: puts the highlight on its first button. */
    @Volatile var focusBanner: (() -> Boolean)? = null
    /** The side menu is open. */
    @Volatile var menuOpen: Boolean = false
}
