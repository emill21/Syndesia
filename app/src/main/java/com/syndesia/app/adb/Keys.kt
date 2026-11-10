package com.syndesia.app.adb

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * The phone's ADB identity: an RSA key and a self-signed certificate around its public half.
 * Watches remember this key once paired (or once "Always allow" is tapped), so it is made once
 * and kept in the app's private storage.
 */
object Keys {
    private const val KEY_FILE = "adb_key.pk8"
    private const val CERT_FILE = "adb_cert.der"

    @Synchronized
    fun load(context: Context): Pair<PrivateKey, Certificate> {
        val dir = context.applicationContext.filesDir
        val keyFile = File(dir, KEY_FILE)
        val certFile = File(dir, CERT_FILE)
        if (keyFile.exists() && certFile.exists()) {
            runCatching {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
                return key to cert
            }
        }
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
        val pair = gen.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=Syndesia")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(SecureRandom().nextLong() and Long.MAX_VALUE),
            Date(now - 86_400_000L),
            Date(now + 30L * 365 * 86_400_000L),
            name,
            pair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        keyFile.writeBytes(pair.private.encoded)
        certFile.writeBytes(cert.encoded)
        return pair.private to cert
    }

    /** Forget this phone's identity. Every watch will need pairing (or approving) again. */
    @Synchronized
    fun reset(context: Context) {
        val dir = context.applicationContext.filesDir
        File(dir, KEY_FILE).delete()
        File(dir, CERT_FILE).delete()
    }
}
