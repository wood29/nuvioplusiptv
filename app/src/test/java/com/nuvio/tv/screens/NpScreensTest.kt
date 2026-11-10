package com.nuvio.tv.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.NpFeaturedBannerHost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Nuvio Plus: renders new screens at TV size (1920×1080) to PNG files, so they can be looked
 * at before a release. Not a check of anything by itself: the pictures are the result.
 * Run: ./gradlew :app:testFullDebugUnitTest --tests '*NpScreensTest*'  → build/np-screens/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi", application = android.app.Application::class)
class NpScreensTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val out = File("build/np-screens").apply { mkdirs() }

    private fun picture(name: String, w: Int, h: Int, a: Int, b: Int): String {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a, b, Shader.TileMode.CLAMP)
        })
        // A "face" so crops show: a light circle in the upper middle.
        c.drawCircle(w * 0.62f, h * 0.32f, minOf(w, h) * 0.14f, Paint().apply { color = 0xFFE8C6A8.toInt() })
        val f = File(out, "src_$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "file://" + f.absolutePath
    }

    private fun meta(id: String, name: String, backdrop: String?, poster: String?) = MetaPreview(
        id = id, type = ContentType.MOVIE, name = name, poster = poster, posterShape = PosterShape.POSTER,
        background = backdrop, logo = null,
        description = "A long-lost soldier fights his way home across a hostile sea, while the family he left behind holds out against the suitors who want his throne.",
        releaseInfo = "2026", imdbRating = 7.9f, genres = listOf("Adventure", "Drama")
    )

    private fun settle() {
        rule.mainClock.autoAdvance = false
        repeat(30) {
            Thread.sleep(80)
            ShadowLooper.idleMainLooper()
            rule.mainClock.advanceTimeBy(100)
        }
    }

    // The window drawn straight into a picture (Compose's own capture waits for a frame the
    // paused test clock never produces).
    private fun save(name: String) {
        rule.runOnUiThread {
            val v = rule.activity.window.decorView
            val img = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            v.draw(Canvas(img))
            File(out, "$name.png").outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // And Compose's own capture, with the clock running again (a second opinion).
        runCatching {
            rule.mainClock.autoAdvance = true
            val img = rule.onRoot().captureToImage().asAndroidBitmap()
            File(out, "${name}_capture.png").outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun homeBanner() {
        val items = listOf(
            meta("tmdb:1", "The Odyssey", picture("wide1", 1280, 720, 0xFF3A2A50.toInt(), 0xFFB04030.toInt()), null),
            meta("tmdb:2", "Second Title", picture("wide2", 1280, 720, 0xFF103040.toInt(), 0xFF40A090.toInt()), null),
            meta("tmdb:3", "Third Title", picture("wide3", 1280, 720, 0xFF202020.toInt(), 0xFF808080.toInt()), null)
        )
        rule.setContent {
            NpFeaturedBannerHost(
                items = items,
                focusedRowKey = { "cw" },
                label = "Recommended for you",
                onOpen = {},
                onWatchlist = {},
                inWatchlist = { false }
            ) {
                Column(Modifier.fillMaxSize().background(Color(0xFF0A0D12)).padding(40.dp)) {
                    Text("Continue Watching", color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(4) { Box(Modifier.size(200.dp, 112.dp).background(Color(0xFF2A3241))) }
                    }
                }
            }
        }
        settle()
        save("home_banner")
    }

    @Test
    fun posterOnlyHero() {
        val poster = picture("poster", 600, 900, 0xFFB02050.toInt(), 0xFF301020.toInt())
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF0A0D12))) {
                com.nuvio.tv.ui.screens.home.ModernHeroMediaLayer(
                    heroBackdrop = { poster },
                    enrichmentActive = { false },
                    shouldPlayHeroTrailer = { false },
                    heroTrailerFirstFrameRendered = { false },
                    heroTrailerUrl = { null },
                    heroTrailerAudioUrl = { null },
                    heroTrailerPlaybackKey = { null },
                    muted = { true },
                    onTrailerEnded = {},
                    onFirstFrameRendered = {},
                    modifier = Modifier.fillMaxSize(),
                    requestWidthPx = 1920,
                    requestHeightPx = 1080
                )
                Text("Title, details and rows are drawn over this by Home", color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(40.dp))
            }
        }
        settle()
        save("poster_only_hero")
    }
}
