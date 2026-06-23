/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.apk

import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.permission.PermissionAllowlist
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Receiver-side [ApkApplyProvider] (ADR-006 D1/D3/D6): streamed decode → stage → reconcile → AC-18
 * skip → capability branch → Tier-0 emit, with stage→verify→act→wipe on every path. All seams are
 * fakes; no Android, no `:adb-bridge`.
 */
class ApkApplyProviderTest {

    private lateinit var stagingDir: File

    @Before
    fun setUp() {
        stagingDir = File.createTempFile("apk-apply-test", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    }

    @After
    fun tearDown() {
        stagingDir.deleteRecursively()
    }

    /** Every staged split file left under the staging root — empty once stage→act→wipe has run. */
    private fun stagedFiles(): List<File> =
        stagingDir.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }

    private val pixelTarget = ApkTargetConfig(
        supportedAbis = listOf("arm64_v8a"),
        densityBucket = "xxhdpi",
        locales = listOf("en"),
    )

    /** A source file for [ApkCodec.writeContainer] with a fixed byte payload. */
    private fun source(entry: ApkFileEntry, bytes: ByteArray): ApkSourceFile =
        ApkSourceFile(entry) { ByteArrayInputStream(bytes) }

    /** Encode a container to a stream the apply provider can read. */
    private fun container(
        packageName: String = "com.example.app",
        versionCode: Long = 7L,
        capturedPermissions: List<String> = emptyList(),
        files: List<Pair<ApkFileEntry, ByteArray>>,
    ): InputStream {
        val header = ApkContainerHeader(packageName, versionCode, files.size, capturedPermissions)
        val out = ByteArrayOutputStream()
        ApkCodec.writeContainer(out, header, files.map { source(it.first, it.second) })
        return ByteArrayInputStream(out.toByteArray())
    }

    /** A silent installer that always reports a successful silent install. */
    private val silentInstalls = ApkSilentInstaller { _, _ -> ApkInstallResult.Installed }

    private val camera = "android.permission.CAMERA"
    private val writeSecure = "android.permission.WRITE_SECURE_SETTINGS"

    /**
     * Records the runtime-permission grants the apply provider requests and returns a scripted granted
     * subset. Defaults to granting everything it is asked (the bridge-says-yes case).
     */
    private class RecordingGranter(
        private val outcome: (List<String>) -> Set<String> = { it.toSet() },
    ) : RuntimePermissionGranter {
        var calls = 0
        var lastPackage: String? = null
        var lastPermissions: List<String> = emptyList()

        override suspend fun grant(packageName: String, permissions: List<String>): Set<String> {
            calls++
            lastPackage = packageName
            lastPermissions = permissions
            return outcome(permissions)
        }
    }

    /** Encode one wire line (JSON + '\n') for hand-built malformed fixtures the codec would refuse to emit. */
    private fun <T> jsonLine(serializer: kotlinx.serialization.KSerializer<T>, value: T): ByteArray =
        (com.ventouxlabs.portage.providers.wire.JsonLines.format.encodeToString(serializer, value) + "\n")
            .toByteArray(Charsets.UTF_8)

    private fun base(len: Int = 4) = ApkFileEntry("base", ApkFileRole.BASE, length = len.toLong())
    private fun abi(name: String, len: Int = 4) =
        ApkFileEntry("split_config.$name", ApkFileRole.CONFIG, abi = name, length = len.toLong())

    private fun provider(
        installed: InstalledPackageVersions = InstalledPackageVersions.None,
        silent: ApkSilentInstaller = ApkSilentInstaller.Deferred,
        hasSilent: () -> Boolean = { false },
        granter: RuntimePermissionGranter = RuntimePermissionGranter.NoOp,
        targetDeclared: TargetDeclaredPermissions = TargetDeclaredPermissions.None,
        onApkInstall: (ApkInstallAction) -> Unit = {},
        onStoreFallback: ((String, String) -> Unit)? = null,
        onPermissionsRestored: ((String, List<String>) -> Unit)? = null,
    ) = ApkApplyProvider(
        stagingDir = stagingDir,
        targetConfig = { pixelTarget },
        installedVersions = installed,
        silentInstaller = silent,
        hasSilentInstall = hasSilent,
        permissionGranter = granter,
        targetDeclaredPermissions = targetDeclared,
        onApkInstall = onApkInstall,
        onStoreFallback = onStoreFallback,
        onPermissionsRestored = onPermissionsRestored,
    )

    @Test
    fun `a container with duplicate split names is rejected`() = runTest {
        // Two entries with the same name must be rejected by validatedEntriesOrNull (fix 1 — hostile
        // container integrity). This test WOULD PASS incorrectly without the duplicate-name guard,
        // because the name-keyed join in fix 2 would silently drop one entry.
        val dupEntry = ApkFileEntry("split_config.arm64_v8a", ApkFileRole.CONFIG, abi = "arm64_v8a", length = 4)
        val header = ApkContainerHeader("com.example.app", 1L, 3)
        val out = ByteArrayOutputStream()
        ApkCodec.writeContainer(
            out, header,
            listOf(
                source(base(), ByteArray(4)),
                source(dupEntry, ByteArray(4)),
                source(dupEntry, ByteArray(4)), // duplicate name — must be rejected
            ),
        )
        val outcome = provider().apply(ByteArrayInputStream(out.toByteArray()))
        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
    }

    @Test
    fun `a streamed-length mismatch is WRITE_ERROR and wipes staging`() = runTest {
        // Declare length 8 but only provide 4 bytes → truncated frame (security M1 carry-forward).
        // The frames are written DIRECTLY rather than via ApkCodec.writeContainer, whose own
        // declared-vs-streamed guard refuses to encode a desynced container — exactly the desync the
        // RECEIVER must catch, so the fixture must be built by hand to exercise the apply-side M1 check.
        val out = ByteArrayOutputStream()
        out.write(jsonLine(ApkContainerHeader.serializer(), ApkContainerHeader("com.example.app", 7L, 1)))
        out.write(jsonLine(ApkFileEntry.serializer(), base(len = 8)))
        out.write(ByteArray(4)) // only 4 of the declared 8 bytes
        val outcome = provider().apply(ByteArrayInputStream(out.toByteArray()))
        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(stagedFiles()).isEmpty()
    }

    @Test
    fun `a hostile header is rejected as WRITE_ERROR`() = runTest {
        // A package name that is not the package grammar (a path) must be refused by validatedHeaderOrNull.
        val header = ApkContainerHeader("../etc/passwd", 1L, 1)
        val out = ByteArrayOutputStream()
        ApkCodec.writeContainer(out, header, listOf(source(base(), ByteArray(4))))
        val outcome = provider().apply(ByteArrayInputStream(out.toByteArray()))
        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
    }

    @Test
    fun `AC-18 skips an app installed at an equal or higher version`() = runTest {
        val installed = InstalledPackageVersions { if (it == "com.example.app") 7L else null }
        val outcome = provider(installed = installed)
            .apply(container(versionCode = 7L, files = listOf(base() to ByteArray(4))))
        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(outcome.detail).contains("already installed")
    }

    @Test
    fun `AC-18 proceeds when the installed version is lower`() = runTest {
        val installed = InstalledPackageVersions { 5L }
        var emitted: ApkInstallAction? = null
        val outcome = provider(installed = installed, onApkInstall = { emitted = it })
            .apply(container(versionCode = 7L, files = listOf(base() to ByteArray(4))))
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(emitted).isNotNull()
    }

    @Test
    fun `AC-15 missing-required-ABI routes to the incompatible store fallback, no install`() = runTest {
        var emitted: ApkInstallAction? = null
        var fallback: Pair<String, String>? = null
        val files = listOf(
            base() to ByteArray(4),
            abi("x86_64") to ByteArray(4), // not in target supportedAbis
        )
        val outcome = provider(
            onApkInstall = { emitted = it },
            onStoreFallback = { pkg, label -> fallback = pkg to label },
        ).apply(container(files = files))
        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(outcome.detail).contains("incompatible")
        assertThat(emitted).isNull()
        assertThat(fallback?.first).isEqualTo("com.example.app")
    }

    @Test
    fun `AC-15 keeps only the matching split subset in the emitted action`() = runTest {
        var emitted: ApkInstallAction? = null
        val files = listOf(
            base() to ByteArray(4),
            abi("arm64_v8a") to ByteArray(4),
            abi("x86_64") to ByteArray(4),
        )
        provider(onApkInstall = { emitted = it }).apply(container(files = files))
        assertThat(emitted?.files?.map { it.name })
            .containsExactly("base", "split_config.arm64_v8a")
    }

    @Test
    fun `capability present plus a silent installer that installs takes the silent path and wipes`() = runTest {
        var silentCalled = false
        val silent = ApkSilentInstaller { _, _ -> silentCalled = true; ApkInstallResult.Installed }
        var emitted = false
        val outcome = provider(
            silent = silent,
            hasSilent = { true },
            onApkInstall = { emitted = true },
        ).apply(container(files = listOf(base() to ByteArray(4))))
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(silentCalled).isTrue()
        assertThat(emitted).isFalse() // silent success means NO Tier-0 emit
        assertThat(stagedFiles()).isEmpty()
    }

    @Test
    fun `a Deferred silent result falls through to the Tier-0 emit`() = runTest {
        var silentCalled = false
        val silent = ApkSilentInstaller { _, _ -> silentCalled = true; ApkInstallResult.Deferred }
        var emitted: ApkInstallAction? = null
        val outcome = provider(
            silent = silent,
            hasSilent = { true },
            onApkInstall = { emitted = it },
        ).apply(container(files = listOf(base() to ByteArray(4))))
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(silentCalled).isTrue()
        assertThat(emitted).isNotNull()
    }

    @Test
    fun `a BridgeUnavailable silent result falls through to the Tier-0 emit (stale positive)`() = runTest {
        val silent = ApkSilentInstaller { _, _ -> ApkInstallResult.BridgeUnavailable }
        var emitted: ApkInstallAction? = null
        provider(silent = silent, hasSilent = { true }, onApkInstall = { emitted = it })
            .apply(container(files = listOf(base() to ByteArray(4))))
        assertThat(emitted).isNotNull()
    }

    @Test
    fun `a Failed silent result surfaces a WRITE_ERROR (not a silent Tier-0 retry)`() = runTest {
        val silent = ApkSilentInstaller { _, _ -> ApkInstallResult.Failed("rejected by policy") }
        var emitted = false
        val outcome = provider(silent = silent, hasSilent = { true }, onApkInstall = { emitted = true })
            .apply(container(files = listOf(base() to ByteArray(4))))
        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(outcome.detail).contains("rejected by policy")
        assertThat(emitted).isFalse()
    }

    @Test
    fun `no SILENT_INSTALL capability goes straight to Tier-0, never calling the silent seam`() = runTest {
        var silentCalled = false
        val silent = ApkSilentInstaller { _, _ -> silentCalled = true; ApkInstallResult.Installed }
        var emitted: ApkInstallAction? = null
        provider(
            silent = silent,
            hasSilent = { false },
            onApkInstall = { emitted = it },
        ).apply(container(files = listOf(base() to ByteArray(4))))
        assertThat(silentCalled).isFalse()
        assertThat(emitted).isNotNull()
    }

    @Test
    fun `reconcile honors the NAME-derived abi, not a mislabeled wire tag (derive-never-trust)`() = runTest {
        // A split NAMED config.x86 but mislabeled on the wire as abi=arm64_v8a (the only target ABI).
        // Trusting the wire tag would KEEP it (and emit a Tier-0 install); deriving from the name yields
        // abi=x86, which does NOT match the arm64-only target, so the sole abi split is unmatched →
        // Incompatible → store fallback, no install. This pins finding 3.
        val mislabeled = ApkFileEntry("config.x86", ApkFileRole.CONFIG, abi = "arm64_v8a", length = 4)
        var emitted: ApkInstallAction? = null
        var fallback: Pair<String, String>? = null
        val outcome = provider(
            onApkInstall = { emitted = it },
            onStoreFallback = { pkg, label -> fallback = pkg to label },
        ).apply(container(files = listOf(base() to ByteArray(4), mislabeled to ByteArray(4))))
        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(outcome.detail).contains("incompatible")
        assertThat(emitted).isNull()
        assertThat(fallback?.first).isEqualTo("com.example.app")
    }

    @Test
    fun `each emitted split carries the correct bytes for its own entry (name-keyed join pin)`() = runTest {
        // Distinct payloads per split — the join must map each entry to ITS OWN staged file, never
        // swapping bytes between splits. This test pins fix 2: remove the name-keyed join and it would
        // either produce wrong bytes or crash on the invariant guard.
        // IMPORTANT: the bytes must be read INSIDE the onApkInstall callback because apply()'s finally
        // wipes the staged files once apply() returns (stage→act→wipe). This mirrors the real
        // PackageInstallerApkInstaller which also reads synchronously inside onApkInstall.
        val baseBytes = ByteArray(4) { 0xAA.toByte() }
        val abiBytes = ByteArray(6) { 0xBB.toByte() }
        val readBaseBytes = mutableListOf<Byte>()
        val readAbiBytes = mutableListOf<Byte>()
        provider(onApkInstall = { action ->
            val byName = action.files.associateBy { it.name }
            byName["base"]?.open()?.use { it.readBytes() }?.let { readBaseBytes.addAll(it.toList()) }
            byName["split_config.arm64_v8a"]?.open()?.use { it.readBytes() }?.let { readAbiBytes.addAll(it.toList()) }
        }).apply(
            container(
                files = listOf(
                    base(len = 4) to baseBytes,
                    abi("arm64_v8a", len = 6) to abiBytes,
                ),
            ),
        )
        assertThat(readBaseBytes).isEqualTo(baseBytes.toList())
        assertThat(readAbiBytes).isEqualTo(abiBytes.toList())
    }

    @Test
    fun `the silent installer receives every reconciled split with re-openable byte streams (AC-11 lifecycle)`() = runTest {
        // Capture the files the silent contract is invoked with; assert each opens to its declared length.
        val openedLengths = mutableListOf<Long>()
        val silent = ApkSilentInstaller { _, files ->
            files.forEach { f ->
                val bytes = f.open().use(InputStream::readBytes)
                openedLengths += bytes.size.toLong()
                assertThat(bytes.size.toLong()).isEqualTo(f.length)
            }
            ApkInstallResult.Installed
        }
        provider(silent = silent, hasSilent = { true }).apply(
            container(
                files = listOf(
                    base(len = 4) to ByteArray(4),
                    abi("arm64_v8a", len = 6) to ByteArray(6),
                ),
            ),
        )
        assertThat(openedLengths).containsExactly(4L, 6L).inOrder()
    }

    // ── runtime-permission parity (ADR-006 D5, Phase 5b-2) ─────────────────────────────────────────

    @Test
    fun `silent install grants only captured default-safe perms the target declares`() = runTest {
        // captured = {INTERNET (default-safe), CAMERA (dangerous→opt-in), WRITE_SECURE_SETTINGS (never)},
        // OTHER_SENSORS is default-safe but NOT captured. Target declares all of them. Only INTERNET is
        // in captured ∩ targetDeclared ∩ DEFAULT_SAFE → only INTERNET is granted.
        val granter = RecordingGranter()
        val outcome = provider(
            silent = silentInstalls,
            hasSilent = { true },
            granter = granter,
            targetDeclared = TargetDeclaredPermissions {
                setOf(PermissionAllowlist.INTERNET, PermissionAllowlist.OTHER_SENSORS, camera, writeSecure)
            },
        ).apply(
            container(
                capturedPermissions = listOf(PermissionAllowlist.INTERNET, camera, writeSecure),
                files = listOf(base() to ByteArray(4)),
            ),
        )
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(granter.calls).isEqualTo(1)
        assertThat(granter.lastPackage).isEqualTo("com.example.app")
        assertThat(granter.lastPermissions).containsExactly(PermissionAllowlist.INTERNET)
        assertThat(outcome.detail).contains("restored 1/1 runtime permissions")
    }

    @Test
    fun `both default-safe perms are granted when captured and target-declared`() = runTest {
        val granter = RecordingGranter()
        val outcome = provider(
            silent = silentInstalls,
            hasSilent = { true },
            granter = granter,
            targetDeclared = TargetDeclaredPermissions {
                setOf(PermissionAllowlist.INTERNET, PermissionAllowlist.OTHER_SENSORS)
            },
        ).apply(
            container(
                capturedPermissions = listOf(PermissionAllowlist.INTERNET, PermissionAllowlist.OTHER_SENSORS),
                files = listOf(base() to ByteArray(4)),
            ),
        )
        assertThat(granter.lastPermissions)
            .containsExactly(PermissionAllowlist.INTERNET, PermissionAllowlist.OTHER_SENSORS)
        assertThat(outcome.detail).contains("restored 2/2 runtime permissions")
    }

    @Test
    fun `a default-safe perm the target does not declare is not granted`() = runTest {
        // captured INTERNET but the target declares only OTHER_SENSORS → intersection empty → no grant,
        // and the granter is never invoked (the apply short-circuits before any privileged call).
        val granter = RecordingGranter()
        val outcome = provider(
            silent = silentInstalls,
            hasSilent = { true },
            granter = granter,
            targetDeclared = TargetDeclaredPermissions { setOf(PermissionAllowlist.OTHER_SENSORS) },
        ).apply(
            container(
                capturedPermissions = listOf(PermissionAllowlist.INTERNET),
                files = listOf(base() to ByteArray(4)),
            ),
        )
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(granter.calls).isEqualTo(0)
        assertThat(outcome.detail).isEqualTo("installed com.example.app silently")
    }

    @Test
    fun `the Tier-0 fallback never grants runtime permissions`() = runTest {
        // No silent capability → Tier-0 emit. Grants belong ONLY to the silent-install success path
        // (no live bridge here, and the install is still pending the user's confirm tap).
        val granter = RecordingGranter()
        var emitted: ApkInstallAction? = null
        val outcome = provider(
            hasSilent = { false },
            granter = granter,
            targetDeclared = TargetDeclaredPermissions { setOf(PermissionAllowlist.INTERNET) },
            onApkInstall = { emitted = it },
        ).apply(
            container(
                capturedPermissions = listOf(PermissionAllowlist.INTERNET),
                files = listOf(base() to ByteArray(4)),
            ),
        )
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(emitted).isNotNull()
        assertThat(granter.calls).isEqualTo(0)
    }

    @Test
    fun `a failed silent install never grants runtime permissions`() = runTest {
        val granter = RecordingGranter()
        val silent = ApkSilentInstaller { _, _ -> ApkInstallResult.Failed("rejected by policy") }
        val outcome = provider(
            silent = silent,
            hasSilent = { true },
            granter = granter,
            targetDeclared = TargetDeclaredPermissions { setOf(PermissionAllowlist.INTERNET) },
        ).apply(
            container(
                capturedPermissions = listOf(PermissionAllowlist.INTERNET),
                files = listOf(base() to ByteArray(4)),
            ),
        )
        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(granter.calls).isEqualTo(0)
    }

    @Test
    fun `a deferred silent result (Tier-0 fall-through) never grants runtime permissions`() = runTest {
        val granter = RecordingGranter()
        val silent = ApkSilentInstaller { _, _ -> ApkInstallResult.Deferred }
        provider(
            silent = silent,
            hasSilent = { true },
            granter = granter,
            targetDeclared = TargetDeclaredPermissions { setOf(PermissionAllowlist.INTERNET) },
        ).apply(
            container(
                capturedPermissions = listOf(PermissionAllowlist.INTERNET),
                files = listOf(base() to ByteArray(4)),
            ),
        )
        assertThat(granter.calls).isEqualTo(0)
    }

    @Test
    fun `a best-effort grant failure keeps the install OK`() = runTest {
        // The bridge granted nothing (every pm grant failed). The install still succeeded, so the
        // outcome stays OK — a failed parity grant is never fatal (ADR-006 D5).
        val granter = RecordingGranter(outcome = { emptySet() })
        val outcome = provider(
            silent = silentInstalls,
            hasSilent = { true },
            granter = granter,
            targetDeclared = TargetDeclaredPermissions {
                setOf(PermissionAllowlist.INTERNET, PermissionAllowlist.OTHER_SENSORS)
            },
        ).apply(
            container(
                capturedPermissions = listOf(PermissionAllowlist.INTERNET, PermissionAllowlist.OTHER_SENSORS),
                files = listOf(base() to ByteArray(4)),
            ),
        )
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(granter.calls).isEqualTo(1)
        assertThat(outcome.detail).contains("restored 0/2 runtime permissions")
    }

    @Test
    fun `no captured permissions means no grant attempt and an unchanged detail`() = runTest {
        val granter = RecordingGranter()
        val outcome = provider(
            silent = silentInstalls,
            hasSilent = { true },
            granter = granter,
            targetDeclared = TargetDeclaredPermissions { setOf(PermissionAllowlist.INTERNET) },
        ).apply(container(files = listOf(base() to ByteArray(4))))
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(granter.calls).isEqualTo(0)
        assertThat(outcome.detail).isEqualTo("installed com.example.app silently")
    }

    @Test
    fun `onPermissionsRestored reports the package and restored perms after a silent grant`() = runTest {
        val restored = mutableListOf<Pair<String, List<String>>>()
        provider(
            silent = silentInstalls,
            hasSilent = { true },
            granter = RecordingGranter(),
            targetDeclared = TargetDeclaredPermissions {
                setOf(PermissionAllowlist.INTERNET, PermissionAllowlist.OTHER_SENSORS)
            },
            onPermissionsRestored = { pkg, perms -> restored += pkg to perms },
        ).apply(
            container(
                capturedPermissions = listOf(PermissionAllowlist.INTERNET, PermissionAllowlist.OTHER_SENSORS),
                files = listOf(base() to ByteArray(4)),
            ),
        )
        assertThat(restored).hasSize(1)
        assertThat(restored.single().first).isEqualTo("com.example.app")
        assertThat(restored.single().second)
            .containsExactly(PermissionAllowlist.INTERNET, PermissionAllowlist.OTHER_SENSORS).inOrder()
    }

    @Test
    fun `onPermissionsRestored is not invoked when the best-effort grant restores nothing`() = runTest {
        val restored = mutableListOf<Pair<String, List<String>>>()
        provider(
            silent = silentInstalls,
            hasSilent = { true },
            granter = RecordingGranter(outcome = { emptySet() }), // every pm grant failed
            targetDeclared = TargetDeclaredPermissions { setOf(PermissionAllowlist.INTERNET) },
            onPermissionsRestored = { pkg, perms -> restored += pkg to perms },
        ).apply(
            container(
                capturedPermissions = listOf(PermissionAllowlist.INTERNET),
                files = listOf(base() to ByteArray(4)),
            ),
        )
        assertThat(restored).isEmpty()
    }

    @Test
    fun `onPermissionsRestored is not invoked on the Tier-0 fallback`() = runTest {
        val restored = mutableListOf<Pair<String, List<String>>>()
        provider(
            hasSilent = { false },
            granter = RecordingGranter(),
            targetDeclared = TargetDeclaredPermissions { setOf(PermissionAllowlist.INTERNET) },
            onPermissionsRestored = { pkg, perms -> restored += pkg to perms },
        ).apply(
            container(
                capturedPermissions = listOf(PermissionAllowlist.INTERNET),
                files = listOf(base() to ByteArray(4)),
            ),
        )
        assertThat(restored).isEmpty()
    }
}
