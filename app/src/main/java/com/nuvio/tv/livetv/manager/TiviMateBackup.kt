package com.nuvio.tv.livetv.manager

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Nuvio Plus: reads a TiviMate backup (.tmb: a zip with TiviMate's database, TvPlayer.db) and
 * finds the favourites and your own groups in it, by channel name. Nothing is changed here: the
 * Channel Manager page matches the names to this box's channels and you send them to the TV as
 * usual. TiviMate's database layout isn't published, so the tables are recognised by their
 * names and columns; what was found (and every table) is reported, so a miss can be looked at.
 */
object TiviMateBackup {

    fun read(context: Context, bytes: ByteArray): JSONObject {
        val dbFile = File(context.cacheDir, "tivimate_import.db")
        dbFile.delete()
        var found = false
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (!e.isDirectory && (name.equals("TvPlayer.db", true) || (!found && name.endsWith(".db", true)))) {
                    dbFile.outputStream().use { zip.copyTo(it) }
                    found = true
                    if (name.equals("TvPlayer.db", true)) break
                }
            }
        }
        if (!found) error("That isn't a TiviMate backup (no TiviMate database inside). Pick the .tmb file TiviMate saved.")
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return readDb(db)
        } finally {
            db.close()
            dbFile.delete()
        }
    }

    private fun readDb(db: SQLiteDatabase): JSONObject {
        val tables = HashMap<String, List<String>>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
            while (c.moveToNext()) {
                val t = c.getString(0)
                if (t.startsWith("sqlite_") || t == "android_metadata" || t == "room_master_table") continue
                tables[t] = columns(db, t)
            }
        }
        fun col(cols: List<String>, vararg want: String): String? =
            want.firstNotNullOfOrNull { w -> cols.firstOrNull { it.equals(w, true) } }
                ?: want.firstNotNullOfOrNull { w -> cols.firstOrNull { it.contains(w, true) } }

        // The channels table: has a name and a stream address (or at least "channel" in its name).
        val channelsTable = tables.entries
            .filter { (t, cols) -> col(cols, "name", "title") != null && (t.contains("channel", true)) }
            .maxByOrNull { (_, cols) -> (if (col(cols, "url", "stream") != null) 2 else 0) + (if (col(cols, "_id", "id") != null) 1 else 0) }
        val chId = channelsTable?.let { col(it.value, "_id", "id") }
        val chName = channelsTable?.let { col(it.value, "name", "title") }
        val chGroup = channelsTable?.let { col(it.value, "group_name", "group_title", "category") }

        fun channelNames(ids: List<Long>): List<Pair<String, String?>> {
            if (channelsTable == null || chId == null || chName == null || ids.isEmpty()) return emptyList()
            val out = HashMap<Long, Pair<String, String?>>()
            ids.distinct().chunked(500).forEach { part ->
                val q = "SELECT \"$chId\", \"$chName\"" + (chGroup?.let { ", \"$it\"" } ?: "") +
                    " FROM \"${channelsTable.key}\" WHERE \"$chId\" IN (${part.joinToString(",")})"
                db.rawQuery(q, null).use { c ->
                    while (c.moveToNext()) out[c.getLong(0)] = c.getString(1).orEmpty() to (if (chGroup != null) c.getString(2) else null)
                }
            }
            return ids.mapNotNull { out[it] }.filter { it.first.isNotBlank() }
        }

        fun orderBy(cols: List<String>): String =
            col(cols, "position", "sort_order", "sort", "order", "idx", "number", "num")?.let { " ORDER BY \"$it\"" } ?: ""

        val favorites = JSONArray()
        val notes = ArrayList<String>()

        // 1. A favourites table pointing at channels (or holding their names).
        val favTable = tables.entries.firstOrNull { (t, _) -> t.contains("fav", true) && !t.contains("group", true) }
            ?: tables.entries.firstOrNull { (t, _) -> t.contains("fav", true) }
        if (favTable != null) {
            val cols = favTable.value
            val ref = cols.firstOrNull { it.contains("channel", true) && it.contains("id", true) }
            val nameCol = col(cols, "channel_name", "name", "title")
            if (ref != null) {
                val ids = ArrayList<Long>()
                db.rawQuery("SELECT \"$ref\" FROM \"${favTable.key}\"" + orderBy(cols), null).use { c -> while (c.moveToNext()) ids += c.getLong(0) }
                channelNames(ids).forEach { (n, g) -> favorites.put(JSONObject().put("name", n).put("group", g ?: "")) }
                notes += "favourites from table ${favTable.key} (${ids.size})"
            } else if (nameCol != null) {
                db.rawQuery("SELECT \"$nameCol\" FROM \"${favTable.key}\"" + orderBy(cols), null).use { c ->
                    while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { favorites.put(JSONObject().put("name", it).put("group", "")) }
                }
                notes += "favourites from table ${favTable.key} by name"
            }
        }
        // 2. Or a favourite mark on the channels themselves.
        if (favorites.length() == 0 && channelsTable != null && chName != null) {
            val mark = channelsTable.value.firstOrNull { it.contains("fav", true) }
            if (mark != null) {
                val q = "SELECT \"$chName\"" + (chGroup?.let { ", \"$it\"" } ?: "") +
                    " FROM \"${channelsTable.key}\" WHERE \"$mark\" IS NOT NULL AND \"$mark\" != 0 ORDER BY \"$mark\""
                db.rawQuery(q, null).use { c ->
                    while (c.moveToNext()) favorites.put(JSONObject().put("name", c.getString(0).orEmpty()).put("group", if (chGroup != null) c.getString(1).orEmpty() else ""))
                }
                notes += "favourites marked in ${channelsTable.key}.$mark"
            }
        }

        // Your own groups: a group table that's yours (custom / user / favourite lists) and a
        // table linking its groups to channels.
        val groups = JSONArray()
        val ownGroupTable = tables.entries.firstOrNull { (t, cols) ->
            t.contains("group", true) && (t.contains("custom", true) || t.contains("user", true) || t.contains("fav", true)) &&
                col(cols, "name", "title") != null && cols.none { it.contains("channel", true) && it.contains("id", true) }
        }
        if (ownGroupTable != null) {
            val gId = col(ownGroupTable.value, "_id", "id")
            val gName = col(ownGroupTable.value, "name", "title")
            val link = tables.entries.firstOrNull { (t, cols) ->
                t != ownGroupTable.key && cols.any { it.contains("group", true) && it.contains("id", true) } &&
                    cols.any { it.contains("channel", true) && it.contains("id", true) }
            }
            if (gId != null && gName != null && link != null) {
                val lg = link.value.first { it.contains("group", true) && it.contains("id", true) }
                val lc = link.value.first { it.contains("channel", true) && it.contains("id", true) }
                db.rawQuery("SELECT \"$gId\", \"$gName\" FROM \"${ownGroupTable.key}\"" + orderBy(ownGroupTable.value), null).use { g ->
                    while (g.moveToNext()) {
                        val id = g.getLong(0)
                        val ids = ArrayList<Long>()
                        db.rawQuery("SELECT \"$lc\" FROM \"${link.key}\" WHERE \"$lg\" = ?" + orderBy(link.value), arrayOf(id.toString())).use { c ->
                            while (c.moveToNext()) ids += c.getLong(0)
                        }
                        val names = channelNames(ids)
                        if (names.isNotEmpty()) groups.put(JSONObject()
                            .put("name", g.getString(1).orEmpty().ifBlank { "TiviMate group" })
                            .put("channels", JSONArray(names.map { JSONObject().put("name", it.first).put("group", it.second ?: "") })))
                    }
                }
                notes += "groups from ${ownGroupTable.key} via ${link.key}"
            }
        }

        return JSONObject()
            .put("favorites", favorites)
            .put("groups", groups)
            .put("notes", JSONArray(notes))
            .put("tables", JSONArray(tables.entries.sortedBy { it.key }.map { (t, cols) -> "$t(${cols.joinToString(",")})" }))
    }

    private fun columns(db: SQLiteDatabase, table: String): List<String> {
        val out = ArrayList<String>()
        db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
            val i = c.getColumnIndex("name")
            while (c.moveToNext()) out += c.getString(i)
        }
        return out
    }
}
