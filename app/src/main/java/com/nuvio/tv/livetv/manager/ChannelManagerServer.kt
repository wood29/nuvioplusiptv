package com.nuvio.tv.livetv.manager

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Nuvio Plus: the Channel Manager. A small web page the box serves on the home network, opened
 * on a PC or tablet: folders, order, numbers, names, hiding and groups removed from the box,
 * arranged with a mouse and applied in one go (confirmed on the TV first). Also saves the whole
 * Live TV setup as a file and loads one back.
 */
class ChannelManagerServer(
    private val context: Context,
    private val stateJson: () -> String,
    private val backupJson: () -> String,
    private val onProposed: (ManagerChange) -> Unit,
    port: Int
) : NanoHTTPD(port) {

    enum class Kind { APPLY, RESTORE }
    enum class Status { PENDING, CONFIRMED, REJECTED }

    class ManagerChange(val id: String, val kind: Kind, val body: JSONObject, val summary: String) {
        @Volatile var status: Status = Status.PENDING
    }

    private val changes = ConcurrentHashMap<String, ManagerChange>()

    fun resolve(id: String, confirmed: Boolean) {
        changes[id]?.status = if (confirmed) Status.CONFIRMED else Status.REJECTED
    }

    override fun serve(session: IHTTPSession): Response = runCatching {
        val uri = session.uri
        val get = session.method == Method.GET
        val post = session.method == Method.POST
        when {
            get && (uri == "/" || uri == "/index.html") -> page()
            get && uri == "/api/hello" -> json(
                JSONObject().put("app", "avonioTV").put("device", android.os.Build.MODEL ?: "TV box").toString()
            )
            get && uri == "/api/state" -> json(stateJson())
            get && uri == "/api/backup" -> json(backupJson()).apply {
                addHeader("Content-Disposition", "attachment; filename=\"nuvio-plus-live-tv-setup.json\"")
            }
            post && uri == "/api/apply" -> propose(Kind.APPLY, session)
            post && uri == "/api/restore" -> propose(Kind.RESTORE, session)
            // A TiviMate backup (.tmb, sent as base64): its favourites and groups, by channel name.
            post && uri == "/api/tivimate" -> {
                val b64 = JSONObject(readBody(session)).optString("data")
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                json(TiviMateBackup.read(context, bytes).toString())
            }
            get && uri.startsWith("/api/status/") -> {
                val c = changes[uri.removePrefix("/api/status/")]
                json(JSONObject().put("status", (c?.status ?: Status.REJECTED).name.lowercase()).toString())
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }.getOrElse { e ->
        newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8",
            JSONObject().put("error", e.message ?: e.toString()).toString()
        )
    }

    private fun page(): Response {
        val html = context.assets.open("channel_manager.html").use { it.readBytes().toString(StandardCharsets.UTF_8) }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun json(text: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", text).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun propose(kind: Kind, session: IHTTPSession): Response {
        val body = JSONObject(readBody(session))
        val summary = body.optString("summary").take(600).ifBlank {
            if (kind == Kind.RESTORE) "Replace this TV's Live TV setup with the file from your PC." else "Apply your changes from avonioTV Studio."
        }
        val change = ManagerChange(UUID.randomUUID().toString(), kind, body, summary)
        // Only the newest proposal counts.
        changes.values.filter { it.status == Status.PENDING }.forEach { it.status = Status.REJECTED }
        changes[change.id] = change
        onProposed(change)
        return json(JSONObject().put("id", change.id).toString())
    }

    private fun readBody(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return "{}"
        require(length <= 40 * 1024 * 1024) { "Too big" }
        val out = ByteArrayOutputStream(length)
        val buf = ByteArray(64 * 1024)
        var left = length
        val input = session.inputStream
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size, left))
            if (n <= 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    companion object {
        fun start(
            context: Context,
            stateJson: () -> String,
            backupJson: () -> String,
            onProposed: (ManagerChange) -> Unit,
            startPort: Int = 8096
        ): ChannelManagerServer? {
            for (port in startPort until startPort + 10) {
                try {
                    val server = ChannelManagerServer(context, stateJson, backupJson, onProposed, port)
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
