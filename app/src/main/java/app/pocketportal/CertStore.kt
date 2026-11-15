package app.pocketportal

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * The phone's own TLS identity: an EC key that never leaves Android's keystore,
 * with a self-signed certificate. The headset app trusts it by its SHA-256
 * fingerprint, which the phone advertises over mDNS and shows on screen.
 */
object CertStore {
    private const val ALIAS = "pocketportal-tls"
    // Browsers reject certificates valid for more than 398 days, even self-signed ones.
    private val VALIDITY = TimeUnit.DAYS.toMillis(397)
    private val RENEW_BEFORE = TimeUnit.DAYS.toMillis(30)

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun sslContext(): SSLContext {
        val ks = keyStore()
        val cert = ks.getCertificate(ALIAS) as? X509Certificate
        if (cert == null || cert.notAfter.time - System.currentTimeMillis() < RENEW_BEFORE) {
            generate()
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(), null)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    /** Lowercase hex SHA-256 of the certificate, or null before the first [sslContext] call. */
    fun fingerprint(): String? {
        val cert = keyStore().getCertificate(ALIAS) ?: return null
        return MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
    }

    private fun generate() {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(
                KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512,
            )
            .setCertificateSubject(X500Principal("CN=Pocket Portal"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - TimeUnit.DAYS.toMillis(1)))
            .setCertificateNotAfter(Date(now + VALIDITY))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(spec)
            generateKeyPair()
        }
    }
}
