# Release procedure

Releases consist of four APKs: sender and receiver, each in `degoogle` and `play`
flavors. The degoogle receiver contains Tier 1; the Play receiver is compile-time
bridge-free.

## One-time repository setup

Create a protected GitHub Actions environment named `release`. Require maintainer approval
and restrict deployment to tags matching `v*`. Add these environment secrets:

| Secret | Value |
|---|---|
| `PORTAGE_KEYSTORE_BASE64` | Base64 of the release JKS/PKCS12 file, without line wrapping |
| `PORTAGE_KEYSTORE_PASSWORD` | Keystore password |
| `PORTAGE_KEY_ALIAS` | Signing-key alias |
| `PORTAGE_KEY_PASSWORD` | Key password |

Keep an offline, access-controlled backup of the keystore and credentials. Losing the key
prevents publishing trusted updates; disclosure permits malicious updates. Do not store
the key or passwords in the repository, build logs, or release artifacts.

Once the keystore exists, record the SHA-256 of its signing certificate and pin it so a
swapped or misconfigured key cannot publish a green release under a different identity:
`apksigner verify --print-certs` prints the digest; the release workflow currently prints
certs at `release.yml` but does not yet compare them to a known-good value. Add an
expected-fingerprint check (e.g. a repo *variable*, not a secret) when the key is created.

## Release gates

Before creating a tag:

1. Confirm `main` CI and the weekly dependency audit are green.
2. Review dependency changes and the generated merged manifests.
3. Obtain independent code review. Obtain an independent security review for changes to
   crypto, permissions, protocol, payload parsing, exported components, or Tier 1.
4. Build release candidates with the intended version:

   ```sh
   ./gradlew assembleRelease \
     -PportageVersionName=0.1.0 \
     -PportageVersionCode=1000 \
     --no-daemon
   ```

5. Run `docs/prp/TRANSFER-RUNBOOK.md` and
   `docs/prp/E2E-VERIFICATION-RUNBOOK.md` on supported GrapheneOS devices using release
   variants. Exercise screen-off transfer, denied Network permission, cancellation,
   corrupt input, Tier-0 install, and Tier-1 degradation.
6. Confirm the version, application IDs, labels, permission prompts, privacy policy, and
   release notes are accurate.

## Publish

Create an annotated semantic-version tag after all gates pass:

```sh
git tag -s v0.1.0 -m "portage 0.1.0"
git push origin v0.1.0
```

The release workflow derives Android `versionCode` as
`major * 1,000,000 + minor * 1,000 + patch`, builds and verifies all four signed APKs,
generates `SHA256SUMS`, and creates the GitHub release. Minor and patch components must be
below 1000.

After publication, install the downloaded APKs on clean devices, verify their signing
certificate and checksums, and repeat a minimal sender-to-receiver smoke test. Do not move
or recreate a tag to replace published binaries; issue a new patch release.

## Local signing

The application modules sign release APKs only when all four environment variables are
present:

```sh
export PORTAGE_KEYSTORE_PATH=/secure/path/portage-release.jks
export PORTAGE_KEYSTORE_PASSWORD=...
export PORTAGE_KEY_ALIAS=...
export PORTAGE_KEY_PASSWORD=...
./gradlew assembleRelease \
  -PportageVersionName=0.1.0 \
  -PportageVersionCode=1000
```

Without them, `assembleRelease` intentionally produces unsigned artifacts suitable for
R8 and manifest validation, not distribution.

Two caveats for local signing:

- Setting only *some* of the four `PORTAGE_*` variables fails configuration for every
  app-module task (including debug/test), by design — set all four or none.
- The signing inputs are read at configuration time, so with the configuration cache enabled
  they can be serialized under `.gradle/`. Run local signed builds with
  `--no-configuration-cache` to keep keystore credentials off disk.
