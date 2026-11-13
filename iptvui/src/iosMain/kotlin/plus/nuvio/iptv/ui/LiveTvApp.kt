/*
 * Nuvio Plus Live TV screens: written from scratch (see iptvui/LICENSE).
 */
package plus.nuvio.iptv.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Told by the app (Swift) side when the Live TV player goes full screen, to hide the tab bar. */
interface LiveTvChromeListener {
    fun fullScreenChanged(fullScreen: Boolean)
}

/**
 * The one Live TV for the app, and the links to the Swift side:
 *  - [tabVisible]: the app says whether the Live TV tab is on screen (and the app in front).
 *    Off screen the stream is stopped, so no sound plays behind other tabs and a
 *    one-connection account is free for the TV; back on screen the channel starts again.
 *  - [register]: the app listens for full screen, to hide its tab bar.
 */
object LiveTvApp {
    private var created = false
    val controller: LiveTvController by lazy { created = true; LiveTvController() }

    private var listener: LiveTvChromeListener? = null
    fun register(listener: LiveTvChromeListener) { this.listener = listener }

    private val _fullScreen = MutableStateFlow(false)
    val fullScreen: StateFlow<Boolean> = _fullScreen.asStateFlow()

    internal fun setFullScreen(on: Boolean) {
        if (_fullScreen.value == on) return
        _fullScreen.value = on
        listener?.fullScreenChanged(on)
    }

    fun tabVisible(visible: Boolean) {
        // Not opened yet: nothing to stop, and no reason to load the channels early.
        if (created) controller.setVisible(visible)
        // Leaving the tab ends full screen, so the tab bar is back everywhere else.
        if (!visible) setFullScreen(false)
    }
}
