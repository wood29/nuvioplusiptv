/*
 * avonioTV core: written from scratch for avonioTV (see core/LICENSE).
 */
package app.avoniotv.core

import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The TV guide saved on the device (compressed, one line per listing), so it shows at once
 * after a start instead of after the provider's large guide file has been read again.
 * Keeps what the app keeps (the box: 3 days back for catch-up, a day ahead).
 */
object GuideCache {
    private const val VERSION = "avguide1"
    private const val SEP = '\u001F'

    private fun file(dir: File?) = dir?.let { File(it, "guide.tsv.gz") }

    private fun clean(v: String?) = v.orEmpty().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun write(dir: File?, url: String, guide: Guide, now: Long) {
        val f = file(dir) ?: return
        dir!!.mkdirs()
        val tmp = File(dir, "guide.tmp")
        GZIPOutputStream(tmp.outputStream().buffered(1 shl 16)).bufferedWriter().use { w ->
            w.write("$VERSION\t${url.hashCode()}\t$now\n")
            guide.channelNames.forEach { (id, names) -> w.write("N\t${clean(id)}\t${names.joinToString(SEP.toString()) { clean(it) }}\n") }
            guide.all().forEach { p ->
                w.write("P\t"); w.write(clean(p.guideId)); w.write("\t"); w.write(p.startMs.toString()); w.write("\t"); w.write(p.stopMs.toString())
                w.write("\t"); w.write(clean(p.title)); w.write("\t"); w.write(clean(p.description)); w.write("\t"); w.write(clean(p.category))
                w.write("\t"); w.write(clean(p.episode)); w.write("\t"); w.write(clean(p.icon)); w.write("\t"); w.write(p.year?.toString().orEmpty())
                w.write("\t"); w.write(p.people.joinToString(SEP.toString()) { clean(it) }); w.write("\n")
            }
        }
        tmp.renameTo(f)
    }

    /** The saved guide and when it was loaded; null when there is none for this guide link. */
    fun read(dir: File?, url: String, now: Long): Pair<Guide, Long>? {
        val f = file(dir) ?: return null
        if (!f.exists()) return null
        val programmes = ArrayList<Programme>()
        val names = HashMap<String, List<String>>()
        val shared = HashMap<String, String>()
        fun share(v: String) = shared.getOrPut(v) { v }
        var loadedAt = 0L
        GZIPInputStream(f.inputStream().buffered(1 shl 16)).bufferedReader().use { r ->
            val head = r.readLine()?.split('\t') ?: return null
            if (head.getOrNull(0) != VERSION || head.getOrNull(1) != url.hashCode().toString()) return null
            loadedAt = head.getOrNull(2)?.toLongOrNull() ?: return null
            val from = now - 7 * LiveTvEngine.DAY
            while (true) {
                val line = r.readLine() ?: break
                val p = line.split('\t')
                when (p[0]) {
                    "N" -> if (p.size >= 3) names[p[1]] = p[2].split(SEP)
                    "P" -> if (p.size >= 11) {
                        val stop = p[3].toLongOrNull() ?: continue
                        if (stop < from) continue
                        programmes += Programme(
                            guideId = share(p[1]), startMs = p[2].toLongOrNull() ?: continue, stopMs = stop,
                            title = p[4], description = p[5].ifEmpty { null }, category = p[6].ifEmpty { null }?.let(::share),
                            episode = p[7].ifEmpty { null }, icon = p[8].ifEmpty { null }?.let(::share), year = p[9].toIntOrNull(),
                            people = if (p[10].isEmpty()) emptyList() else p[10].split(SEP)
                        )
                    }
                }
            }
        }
        if (programmes.isEmpty()) return null
        return Guide(programmes, names) to loadedAt
    }
}
