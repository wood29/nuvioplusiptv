package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Live TV on Android phones isn't part of Nuvio Plus (the TV box app has its own). */
@Composable
internal actual fun LiveTvTabScreen(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Live TV is in the TV box app.") }
}
