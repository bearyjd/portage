/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.adbbridge

import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/** The bridge's ADB identity: RSA-2048 key + the self-signed cert that fronts it for TLS. */
class AdbIdentity internal constructor(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
)

/**
 * Generates and persists the RSA key pair used for ADB auth (ADR-003). The key never leaves
 * [dir] — production wires the app's private `filesDir` (MODE_PRIVATE by construction; both
 * apps set `android:allowBackup="false"`, so it is never captured in an Android backup), and
 * the private key is never logged, serialized elsewhere, or exposed outside :adb-bridge.
 *
 * adbd identifies the client by the PUBLIC key recorded at pairing time (`adb_keys`); the
 * certificate is only the TLS vehicle for proving possession, so a stable long-validity
 * self-signed cert is correct here (trust anchoring happens out-of-band via the pairing code).
 */
class AdbKeyStore(private val dir: File, private val commonName: String = "portage") {

    /** Load the persisted identity, generating and persisting one on first use. */
    @Synchronized
    fun load(): AdbIdentity {
        val keyFile = File(dir, PRIVATE_KEY_FILE)
        val certFile = File(dir, CERTIFICATE_FILE)
        if (keyFile.isFile && certFile.isFile) {
            val loaded = runCatching { read(keyFile, certFile) }.getOrNull()
            if (loaded != null) return loaded
            // Unreadable/corrupt identity: regenerate. The device side will simply require a
            // fresh pairing — safe, and strictly better than failing closed forever.
        }
        return generate().also { persist(it, keyFile, certFile) }
    }

    fun exists(): Boolean = File(dir, PRIVATE_KEY_FILE).isFile && File(dir, CERTIFICATE_FILE).isFile

    private fun read(keyFile: File, certFile: File): AdbIdentity {
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val certificate = certFile.inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        return AdbIdentity(privateKey, certificate)
    }

    private fun generate(): AdbIdentity {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(KEY_BITS, SecureRandom())
        }.generateKeyPair()

        val subject = X500Principal("CN=$commonName")
        val now = System.currentTimeMillis()
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(SERIAL_BITS, SecureRandom()),
            Date(now - BACKDATE_MS),
            Date(now + VALIDITY_MS),
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return AdbIdentity(keyPair.private, certificate)
    }

    private fun persist(identity: AdbIdentity, keyFile: File, certFile: File) {
        dir.mkdirs()
        // Security review 2026-06-12 (MEDIUM): permissions are stripped BEFORE any secret byte
        // lands, and the key is installed by atomic rename — no window where a partially
        // written or group-readable key file exists, even inside app-private storage.
        val staging = File(dir, keyFile.name + ".tmp")
        staging.delete()
        staging.createNewFile()
        staging.setReadable(false, false)
        staging.setReadable(true, true)
        staging.setWritable(false, false)
        staging.setWritable(true, true)
        staging.writeBytes(identity.privateKey.encoded) // PKCS#8
        if (!staging.renameTo(keyFile)) {
            keyFile.delete()
            check(staging.renameTo(keyFile)) { "atomic key install failed" }
        }
        certFile.writeBytes(identity.certificate.encoded) // DER (public half — not sensitive)
    }

    private companion object {
        const val PRIVATE_KEY_FILE = "adb.key"
        const val CERTIFICATE_FILE = "adb.crt"
        const val KEY_BITS = 2048
        const val SERIAL_BITS = 64
        const val SIGNATURE_ALGORITHM = "SHA512withRSA"
        const val BACKDATE_MS = 24L * 60 * 60 * 1000 // tolerate clock skew
        const val VALIDITY_MS = 30L * 365 * 24 * 60 * 60 * 1000 // adbd trusts the key, not the cert chain
    }
}
