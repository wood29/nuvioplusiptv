package com.nuvio.tv.livetv.manager

import android.content.Context
import android.graphics.Bitmap
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.CustomGroup
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nuvio Plus: runs the Channel Manager page (see [ChannelManagerServer]) while it's open on the
 * TV, builds what the page shows and applies what comes back once confirmed on the TV.
 */
@Singleton
class ChannelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: LiveTvRepository,
    private val prefs: LiveTvPreferences
) {
    data class State(
        val active: Boolean = false,
        val qr: Bitmap? = null,
        val url: String? = null,
        val error: String? = null,
        /** A change sent from the PC, waiting for OK on the TV. */
        val pending: ChannelManagerServer.ManagerChange? = null,
        val lastResult: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var server: ChannelManagerServer? = null

    @Volatile private var dialogOpen = false
    @Volatile private var alwaysOn = false

    init {
        // "Allow the Channel Manager App": the page stays reachable while Nuvio Plus runs.
        scope.launch {
            prefs.settings.map { it.channelManagerOnNetwork }.distinctUntilChanged().collect { on ->
                alwaysOn = on
                if (on) ensureServer() else if (!dialogOpen) stopServer()
            }
        }
    }

    /** Opened from the TV's settings: shows the address and QR code. */
    fun start() {
        dialogOpen = true
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _state.value = _state.value.copy(error = "The box isn't connected to a network.", active = false)
            return
        }
        val s = ensureServer()
        if (s == null) {
            _state.value = _state.value.copy(error = "Couldn't open avonioTV Studio's connection (no free network port).", active = false)
            return
        }
        val url = "http://$ip:${s.listeningPort}"
        _state.value = _state.value.copy(active = true, qr = QrCodeGenerator.generate(url, 512), url = url, error = null, lastResult = null)
    }

    /** The TV's Channel Manager screen closed (the page keeps running if allowed on the network). */
    fun stop() {
        dialogOpen = false
        if (!alwaysOn) stopServer()
        _state.value = _state.value.copy(active = false, qr = null, url = null, error = null)
    }

    @Synchronized
    private fun ensureServer(): ChannelManagerServer? {
        server?.let { return it }
        val s = ChannelManagerServer.start(
            context = context,
            stateJson = { runBlocking { buildState() } },
            backupJson = { runBlocking { buildBackup() } },
            onProposed = { change -> _state.value = _state.value.copy(pending = change) }
        )
        server = s
        return s
    }

    @Synchronized
    private fun stopServer() {
        server?.stop()
        server = null
    }

    /** OK or Cancel on the TV for the change sent from the PC. */
    fun resolve(confirmed: Boolean) {
        val change = _state.value.pending ?: return
        _state.value = _state.value.copy(pending = null)
        if (!confirmed) {
            server?.resolve(change.id, false)
            return
        }
        scope.launch {
            val result = runCatching {
                when (change.kind) {
                    ChannelManagerServer.Kind.APPLY -> apply(change.body)
                    ChannelManagerServer.Kind.RESTORE -> restore(change.body)
                }
            }
            server?.resolve(change.id, result.isSuccess)
            _state.value = _state.value.copy(
                lastResult = result.fold({ "Changes from your PC applied." }, { "Couldn't apply the changes: ${it.message}" })
            )
        }
    }

    // ---------------------------------------------------------------- what the page shows

    private suspend fun buildState(): String {
        val channels = repository.channels.value
        val user = prefs.userState.first()
        val playlists = prefs.currentPlaylists()
        val removed = prefs.removedLiveGroupsNow()

        val groupIndex = LinkedHashMap<String, Int>()
        val groups = JSONArray()
        val groupCounts = HashMap<String, Int>()
        channels.forEach { groupCounts[it.groupId] = (groupCounts[it.groupId] ?: 0) + 1 }
        val chArr = JSONArray()
        for (ch in channels) {
            val gi = groupIndex.getOrPut(ch.groupId) {
                groups.put(
                    JSONObject().put("id", ch.groupId).put("title", ch.group).put("src", ch.sourceId)
                        .put("count", groupCounts[ch.groupId] ?: 0)
                )
                groupIndex.size
            }
            chArr.put(JSONArray().put(ch.key).put(ch.name).put(gi).put(ch.number).put(ch.logo ?: ""))
        }

        val plArr = JSONArray()
        val providerGroups = JSONObject()
        val removedObj = JSONObject()
        for (pl in playlists) {
            plArr.put(JSONObject().put("id", pl.id).put("name", pl.name).put("xtream", pl.isXtream))
            providerGroups.put(pl.id, JSONArray(repository.providerGroupNames(pl.id)))
            removedObj.put(pl.id, JSONArray(removed[pl.id].orEmpty().toList()))
        }

        val u = JSONObject()
            .put("favorites", JSONArray(user.favorites))
            .put("customGroups", JSONArray(user.customGroups.map {
                JSONObject().put("id", it.id).put("name", it.name).put("keys", JSONArray(it.channelKeys))
            }))
            .put("groupOrder", JSONArray(user.groupOrder))
            .put("groupNames", JSONObject(user.groupNames))
            .put("channelNames", JSONObject(user.channelNames))
            .put("channelNumbers", JSONObject(user.channelNumbers.mapValues { it.value }))
            .put("hiddenGroups", JSONArray(user.hiddenGroups.toList()))
            .put("hiddenChannels", JSONArray(user.hiddenChannels.toList()))

        return JSONObject()
            .put("playlists", plArr)
            .put("groups", groups)
            .put("channels", chArr)
            .put("user", u)
            .put("removed", removedObj)
            .put("providerGroups", providerGroups)
            .toString()
    }

    private suspend fun buildBackup(): String {
        val removed = JSONObject()
        prefs.removedLiveGroupsNow().forEach { (id, names) -> removed.put(id, JSONArray(names.toList())) }
        return JSONObject()
            .put("app", "avonioTV")
            .put("kind", "live-tv-setup")
            .put("savedAt", System.currentTimeMillis())
            .put("setup", prefs.exportForSync())
            .put("removed", removed)
            // The channel list too, so the file can be opened and edited on a PC without the TV.
            .put("state", JSONObject(buildState()))
            .toString()
    }

    // ---------------------------------------------------------------- applying

    private suspend fun apply(body: JSONObject) {
        val e = body.optJSONObject("edit") ?: error("Nothing to apply")
        fun strList(k: String): List<String>? = e.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
        fun strMap(k: String): Map<String, String>? = e.optJSONObject(k)?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } }
        val numbers = e.optJSONObject("channelNumbers")?.let { o ->
            o.keys().asSequence().mapNotNull { k -> o.optInt(k, 0).takeIf { it > 0 }?.let { k to it } }.toMap()
        }
        val custom = e.optJSONArray("customGroups")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val g = a.optJSONObject(i) ?: return@mapNotNull null
                val keys = g.optJSONArray("keys")
                CustomGroup(
                    id = g.optString("id").ifBlank { return@mapNotNull null },
                    name = g.optString("name").ifBlank { "My group" },
                    channelKeys = if (keys == null) emptyList() else (0 until keys.length()).map { keys.optString(it) }.distinct()
                )
            }
        }
        val removed = e.optJSONObject("removed")?.let { o ->
            o.keys().asSequence().associateWith { k ->
                val a = o.optJSONArray(k) ?: JSONArray()
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
            }
        }
        val changed = prefs.applyChannelManager(
            favorites = strList("favorites"),
            customGroups = custom,
            groupOrder = strList("groupOrder"),
            groupNames = strMap("groupNames"),
            channelNames = strMap("channelNames"),
            channelNumbers = numbers,
            hiddenGroups = strList("hiddenGroups")?.toSet(),
            hiddenChannels = strList("hiddenChannels")?.toSet(),
            removed = removed
        )
        if (changed.isNotEmpty()) repository.downloadAgain(changed)
    }

    private suspend fun restore(body: JSONObject) {
        val file = body.optJSONObject("file") ?: error("No file")
        val setup = file.optJSONObject("setup") ?: error("This isn't an avonioTV Live TV setup file")
        val changed = prefs.importFromSync(setup)
        val removed = file.optJSONObject("removed")?.let { o ->
            o.keys().asSequence().associateWith { k ->
                val a = o.optJSONArray(k) ?: JSONArray()
                (0 until a.length()).map { a.optString(it) }.toSet()
            }
        }
        val changedRemoved = if (removed != null) prefs.applyChannelManager(removed = removed) else emptySet()
        repository.reloadAfterSync(changed + changedRemoved)
    }
}
