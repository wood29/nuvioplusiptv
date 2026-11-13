package com.nuvio.tv.livetv.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * A live TV stream never really ends. Some providers still close the connection every few
 * minutes; the player then sees "end of programme", flushes the decoder and the picture freezes.
 *
 * This wrapper hides that: when the provider closes a live stream, it opens the same address
 * again straight away and carries on feeding bytes, so the player only notices a tiny gap.
 * If reopening keeps failing, the normal "end" reaches the player and its own reconnect runs.
 */
@UnstableApi
class EndlessDataSource(private val upstream: DataSource) : DataSource {

    private var spec: DataSpec? = null
    private var live = false
    private var reopenTimes = ArrayDeque<Long>()

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        val length = upstream.open(dataSpec)
        // Only endless streams: a plain .ts live channel has no known length. Playlists and
        // segments (HLS) do have one and are left alone.
        live = length == C.LENGTH_UNSET.toLong() && dataSpec.position == 0L
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(buffer, offset, length)
        if (n != C.RESULT_END_OF_INPUT || !live) return n
        val s = spec ?: return n
        val now = SystemClock.elapsedRealtime()
        while (reopenTimes.isNotEmpty() && now - reopenTimes.first() > 60_000) reopenTimes.removeFirst()
        if (reopenTimes.size >= MAX_REOPENS_PER_MINUTE) return n
        reopenTimes.addLast(now)
        runCatching { upstream.close() }
        // Providers often refuse for a while (HTTP 403) because they still count the old
        // connection. Keep asking every few seconds instead of giving up: the picture stays
        // where it is and the decoder isn't reset.
        val deadline = now + PATIENCE_MS
        waitingSince = now
        try { while (true) {
            try {
                upstream.open(s)
                return upstream.read(buffer, offset, length)
            } catch (e: Exception) {
                runCatching { upstream.close() }
                if (Thread.currentThread().isInterrupted || SystemClock.elapsedRealtime() > deadline) return C.RESULT_END_OF_INPUT
                try { Thread.sleep(RETRY_GAP_MS) } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt(); return C.RESULT_END_OF_INPUT
                }
            }
        } } finally { waitingSince = 0L }
    }

    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = EndlessDataSource(upstream.createDataSource())
    }

    companion object {
        /** When the provider was last being asked to let us back in (0 = not waiting). */
        @Volatile @JvmStatic var waitingSince = 0L
        private const val MAX_REOPENS_PER_MINUTE = 4
        private const val PATIENCE_MS = 40_000L
        private const val RETRY_GAP_MS = 3_000L
    }
}
