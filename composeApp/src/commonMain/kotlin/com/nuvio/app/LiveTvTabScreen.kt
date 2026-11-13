package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Nuvio Plus: the Live TV tab (iPhone / iPad: the clean IPTV screens in :iptvui). */
@Composable
internal expect fun LiveTvTabScreen(modifier: Modifier)

/** True while the Live TV player is full screen: the app's own tab bars stay out of the way. */
@Composable
internal expect fun rememberLiveTvFullScreen(): Boolean
