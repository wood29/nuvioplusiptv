package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import plus.nuvio.iptv.ui.LiveTvApp
import plus.nuvio.iptv.ui.LiveTvTab

@Composable
internal actual fun LiveTvTabScreen(modifier: Modifier) {
    // One Live TV for the app's lifetime (it keeps the player and the channels).
    LiveTvTab(LiveTvApp.controller, modifier)
}

@Composable
internal actual fun rememberLiveTvFullScreen(): Boolean = LiveTvApp.fullScreen.collectAsState().value
