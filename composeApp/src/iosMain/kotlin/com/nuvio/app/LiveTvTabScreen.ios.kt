package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import plus.nuvio.iptv.ui.LiveTvController
import plus.nuvio.iptv.ui.LiveTvTab

/** One Live TV controller for the app's lifetime (it keeps the player and the channels). */
private val liveTvController by lazy { LiveTvController() }

@Composable
internal actual fun LiveTvTabScreen(modifier: Modifier) {
    LiveTvTab(liveTvController, modifier)
}
