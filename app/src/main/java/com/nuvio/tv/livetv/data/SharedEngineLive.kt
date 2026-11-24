package com.nuvio.tv.livetv.data

import app.avoniotv.core.FileGuideStore
import app.avoniotv.core.LiveTvEngine
import app.avoniotv.core.Source
import com.nuvio.tv.livetv.model.CatchupInfo
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.PlaylistSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient
import java.io.File

/**
 * avonioTV Home on the shared avonioTV engine, step 1 (test app only, BuildConfig.SHARED_ENGINE):
 * the channels and the guide are read by the same code as avonioTV on the TV box, iPad and Quest,
 * then handed to Home's screens in Home's own shapes. Everything else (screens, player, favourites,
 * hidden groups, Nuvio) is unchanged.
 *
 * Channel keys are made the way Home makes them ("playlist|tvg-id|name"), so favourites, hidden
 * channels and the last channel carry over between the two ways of reading.
 */
internal class SharedEngineLive(okHttp: OkHttpClient, filesDir: File) {

    private val http = HttpClient(OkHttp) { engine { preconfigured = okHttp } }

    val engine = LiveTvEngine(
        http,
        clock = { System.currentTimeMillis() },
        guidePastMs = 3 * LiveTvEngine.DAY,
        guideAheadMs = 2 * LiveTvEngine.DAY
    ).also { it.guideStore = FileGuideStore(File(filesDir, "livetv/shared-guide")) }

    /** Home's playlist settings as engine sources (Xtream logins and M3U links). */
    fun sourcesOf(playlists: List<PlaylistSource>): List<Source> = playlists.map { pl ->
        if (pl.isXtream) Source.Xtream(
            id = pl.id, name = pl.name,
            server = pl.xtreamServer.trim().trimEnd('/'),
            username = pl.xtreamUsername.trim(), password = pl.xtreamPassword,
            liveFormat = if (pl.streamFormat == "m3u8") "m3u8" else "ts"
        ) else Source.M3u(
            id = pl.id, name = pl.name, url = pl.resolvedUrl(),
            userAgent = pl.userAgent.ifBlank { null }
        )
    }

    class Result(
        val channels: List<LiveChannel>,
        val programs: Map<String, List<EpgProgram>>,
        val error: String?,
        val guideError: String?
    )

    /**
     * Loads every enabled playlist (the first, then the others added to it) and the guide.
     * [refreshGuide] = false: the saved guide is used while it is under 6 hours old.
     */
    suspend fun load(playlists: List<PlaylistSource>, refreshGuide: Boolean): Result {
        val sources = sourcesOf(playlists)
        if (sources.isEmpty()) return Result(emptyList(), emptyMap(), null, null)
        engine.load(sources.first(), sources.drop(1))
        if (refreshGuide && engine.state.value.lineup != null) engine.refreshGuide()
        val st = engine.state.value
        val lineup = st.lineup ?: return Result(emptyList(), emptyMap(), st.error ?: "Couldn't load the channels", null)
        val byId = playlists.associateBy { it.id }
        val groupName = lineup.groups.associate { it.id to it.name }
        val multi = playlists.size > 1
        val used = HashSet<String>()
        val out = ArrayList<LiveChannel>(lineup.channels.size)
        val programs = HashMap<String, List<EpgProgram>>()
        for (c in lineup.channels) {
            // Added providers' channels carry "<playlist id>:" in front (see LiveTvEngine.load).
            val plId = if (c.sourceId in byId) c.sourceId else c.id.substringBefore(':', lineup.source.id)
            val pl = byId[plId] ?: byId[lineup.source.id] ?: continue
            val group = groupName[c.groupId]?.ifBlank { null } ?: "Uncategorized"
            val base = "${pl.id}|${c.guideId ?: ""}|${c.name}"
            var key = base
            var n = 2
            while (!used.add(key)) key = "$base#${n++}"
            val headers = if (pl.userAgent.isNotBlank() && "User-Agent" !in c.headers) c.headers + ("User-Agent" to pl.userAgent) else c.headers
            out += LiveChannel(
                key = key,
                sourceId = pl.id,
                sourceName = pl.name,
                name = c.name,
                tvgId = c.guideId,
                tvgName = null,
                logo = c.logo,
                groupId = if (multi) "${pl.id}::$group" else group,
                group = group,
                number = c.number,
                url = c.streamUrl,
                headers = headers,
                catchup = if (c.catchupDays > 0) CatchupInfo(type = if (pl.isXtream) "xc" else "default", source = null, days = c.catchupDays) else null
            )
            val listings = st.guide.listings(c)
            if (listings.isNotEmpty()) programs[key] = listings.map {
                EpgProgram(it.startMs, it.stopMs, it.title, it.description, it.category, it.episode, it.icon, it.year, it.people)
            }
        }
        return Result(out, programs, null, st.guideError)
    }
}
