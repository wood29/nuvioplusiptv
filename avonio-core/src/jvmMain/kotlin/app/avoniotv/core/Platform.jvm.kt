/*
 * avonioTV core: written from scratch for avonioTV (see core/LICENSE).
 */
package app.avoniotv.core

internal actual fun withoutAccents(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)

/** The guide as a file in [dir] (the TV box). */
class FileGuideStore(private val dir: java.io.File) : GuideStore {
    override fun read(url: String, now: Long) = GuideCache.read(dir, url, now)
    override fun write(url: String, guide: Guide, now: Long) = GuideCache.write(dir, url, guide, now)
}
