/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.adbbridge

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.interfaces.RSAPublicKey

class AdbKeyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `first load generates a 2048-bit RSA identity`() {
        val store = AdbKeyStore(tmp.newFolder())
        val identity = store.load()

        assertThat(identity.privateKey.algorithm).isEqualTo("RSA")
        val publicKey = identity.certificate.publicKey as RSAPublicKey
        assertThat(publicKey.modulus.bitLength()).isEqualTo(2048)
    }

    @Test
    fun `the certificate is self-signed with the portage subject`() {
        val identity = AdbKeyStore(tmp.newFolder()).load()
        val cert = identity.certificate

        assertThat(cert.subjectX500Principal.name).contains("CN=portage")
        assertThat(cert.issuerX500Principal).isEqualTo(cert.subjectX500Principal)
        cert.verify(cert.publicKey) // throws if not actually self-signed
        cert.checkValidity() // valid now (backdated against clock skew, decades ahead)
    }

    @Test
    fun `the identity persists - a second store over the same dir loads the same key`() {
        val dir = tmp.newFolder()
        val first = AdbKeyStore(dir).load()
        val second = AdbKeyStore(dir).load()

        assertThat(second.certificate.publicKey.encoded)
            .isEqualTo(first.certificate.publicKey.encoded)
        assertThat(second.privateKey.encoded).isEqualTo(first.privateKey.encoded)
    }

    @Test
    fun `exists reflects whether an identity has been generated`() {
        val dir = tmp.newFolder()
        val store = AdbKeyStore(dir)
        assertThat(store.exists()).isFalse()
        store.load()
        assertThat(store.exists()).isTrue()
    }

    @Test
    fun `a corrupt identity regenerates instead of failing closed forever`() {
        val dir = tmp.newFolder()
        val first = AdbKeyStore(dir).load()
        File(dir, "adb.key").writeBytes(byteArrayOf(1, 2, 3)) // corrupt the private key

        val second = AdbKeyStore(dir).load()
        assertThat(second.certificate.publicKey.encoded)
            .isNotEqualTo(first.certificate.publicKey.encoded)
        assertThat(AdbKeyStore(dir).load().privateKey.encoded)
            .isEqualTo(second.privateKey.encoded) // and the regenerated one persists
    }

    @Test
    fun `the private key file never leaves the store directory`() {
        val dir = tmp.newFolder()
        AdbKeyStore(dir).load()
        val files = dir.listFiles()?.map { it.name }?.sorted()
        assertThat(files).containsExactly("adb.crt", "adb.key")
    }
}
