package dev.glowcow.mnt.net

import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** The server shows a certificate other than the one it showed before, and no authority vouches for it; [seen] is its fingerprint. */
class CertificateChangedException(val seen: String) : CertificateException("The server's certificate has changed")

/**
 * Trusts a server the way SSH does: the certificate seen first is kept as [pin] and asked for after; another one passes only
 * when an authority of the system vouches for it and it names [host], as a renewed one does. [onPin] keeps the one trusted.
 */
class PinningTrust(private val host: String, @Volatile private var pin: String, private val onPin: (String) -> Unit) : X509TrustManager {
    /** The fingerprint of the certificate last turned away; null since one passed. */
    @Volatile
    var refused: String? = null

    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("No clients")

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("No certificate")
        val seen = fingerprint(leaf)
        if (seen == pin) {
            refused = null
            return
        }
        if (pin.isNotEmpty() && !(vouched(chain, authType) && names(leaf, host))) {
            refused = seen
            throw CertificateChangedException(seen)
        }
        refused = null
        pin = seen
        onPin(seen)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun vouched(chain: Array<X509Certificate>, authType: String): Boolean = runCatching { system.checkServerTrusted(chain, authType) }.isSuccess

    companion object {
        /** SHA-256 of the certificate, as hex pairs parted by colons. */
        fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

        // Whether the certificate is made out to [host]: one of its names, a wildcard covering one label, or its address.
        internal fun names(certificate: X509Certificate, host: String): Boolean {
            val wanted = host.trim().trimEnd('.').lowercase()
            val alt = runCatching { certificate.subjectAlternativeNames }.getOrNull().orEmpty()
            val address = runCatching { if (wanted.any { it == ':' } || wanted.all { it.isDigit() || it == '.' }) InetAddress.getByName(wanted) else null }.getOrNull()
            return alt.any { entry ->
                val value = (entry.getOrNull(1) as? String)?.lowercase() ?: return@any false
                when (entry.getOrNull(0)) {
                    DNS -> address == null && matches(value.trimEnd('.'), wanted)
                    IP -> address != null && runCatching { InetAddress.getByName(value) == address }.getOrDefault(false)
                    else -> false
                }
            }
        }

        private fun matches(pattern: String, host: String): Boolean {
            if (!pattern.startsWith("*.")) return pattern == host
            val rest = pattern.substring(1)
            return host.endsWith(rest) && host.length > rest.length && '.' !in host.substring(0, host.length - rest.length)
        }

        private const val DNS = 2
        private const val IP = 7
    }
}
