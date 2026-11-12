/*
 * Nuvio Plus Live TV screens: written from scratch (see iptvui/LICENSE).
 */
package plus.nuvio.iptv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import platform.UIKit.UIView

/**
 * The video player for Live TV. The app's Swift side implements it with mpv
 * (LiveMpvPlayer.swift) and registers it with [LivePlayers.register] at start-up.
 */
interface LivePlayer {
    /** The view the video is drawn in. */
    fun view(): UIView
    fun play(url: String, headers: Map<String, String>)
    fun setPaused(paused: Boolean)
    fun stop()
    fun release()
    /** "playing", "buffering", "error: …" or "idle". */
    fun status(): String
}

interface LivePlayerCreator {
    fun create(): LivePlayer
}

object LivePlayers {
    private var creator: LivePlayerCreator? = null
    fun register(creator: LivePlayerCreator) { this.creator = creator }
    fun create(): LivePlayer? = creator?.create()
}

@Composable
internal fun PlayerSurface(player: LivePlayer, modifier: Modifier) {
    UIKitView(factory = { player.view() }, modifier = modifier)
}
