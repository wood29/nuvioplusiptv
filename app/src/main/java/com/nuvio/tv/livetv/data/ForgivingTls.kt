package com.nuvio.tv.livetv.data

import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Downloads of your own playlists and guides, the way other IPTV players do them: a server whose
 * certificate this TV can't check ("Chain validation failed": older boxes don't know newer
 * certificate authorities, or the provider's certificate is out of date or self-made) still works.
 * The TV's own check runs first; only when it fails is the download let through, with a line in
 * the Start-Up Report. The server's name is still checked as usual. Only for the addresses you
 * typed in yourself: nothing of Nuvio's own uses this.
 */
internal object ForgivingTls {
    private const val TAG = "ForgivingTls"
    private val reported = java.util.Collections.synchronizedSet(HashSet<String>())

    // Nuvio Plus: trust on first use. A certificate the TV can't check is let through the first
    // time for that server, and remembered; after that only the same certificate is accepted, so
    // someone in between (a hijacked Wi-Fi, a fake server) can't slip in a different one.
    @Volatile private var pinFile: java.io.File? = null
    private val pins = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Where the remembered certificates are kept (called once, at start). */
    fun init(context: android.content.Context) {
        if (pinFile != null) return
        val f = java.io.File(context.filesDir, "livetv_tls_pins.txt")
        pinFile = f
        runCatching {
            if (f.exists()) f.readLines().forEach { line ->
                val i = line.lastIndexOf('\t')
                if (i > 0) pins[line.substring(0, i)] = line.substring(i + 1)
            }
        }
    }

    private fun savePins() {
        val f = pinFile ?: return
        runCatching { f.writeText(pins.entries.joinToString("\n") { it.key + "\t" + it.value }) }
    }

    private fun fingerprint(cert: X509Certificate): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    private val system: X509TrustManager? = runCatching {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.getOrNull()

    val trustManager: X509TrustManager? = system?.let { sys ->
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                sys.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                try {
                    sys.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    val who = chain?.firstOrNull()?.subjectX500Principal?.name.orEmpty()
                    val leaf = chain?.firstOrNull() ?: throw e
                    val fp = fingerprint(leaf)
                    val known = pins[who]
                    if (known != null && known != fp) {
                        runCatching {
                            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                                "A playlist or guide server sent a different unchecked certificate than before: refused ($who)"
                            )
                        }
                        throw CertificateException("This server's certificate changed and can't be checked on this TV: refused for safety", e)
                    }
                    if (known == null) { pins[who] = fp; savePins() }
                    Log.w(TAG, "Certificate not trusted by this TV, allowed for a playlist or guide: $who", e)
                    // Once per server, not on every connection.
                    if (reported.add(who)) runCatching {
                        com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                            "A playlist or guide server's certificate couldn't be checked on this TV (${e.message ?: "chain validation failed"}); downloaded anyway: $who"
                        )
                    }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = sys.acceptedIssuers
        }
    }

    val socketFactory: SSLSocketFactory? = trustManager?.let { tm ->
        runCatching {
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }.socketFactory
        }.getOrNull()
    }
}
