# Vendored dependency: noise-java

`src/main/java/com/southernstorm/noise/**` is vendored verbatim, not pulled from a
package repository — see `docs/prp/ADR-002-transport-crypto.md` for why (no audited JVM
lib implements modern `pskN`; for a no-cloud security tool we own a pinned copy rather
than ride JitPack-off-master or a third-party fork).

- **Upstream:** https://github.com/rweather/noise-java
- **Pinned commit:** `49377b6dfc6a1e75740bce2318118291a57c0d6e`
- **License:** MIT (Southern Storm Software, Pty Ltd) — see
  `src/main/java/com/southernstorm/noise/LICENSE.txt`.
- **Modifications:** none. Files are unmodified upstream sources.

## Pattern in use

`NoisePSK_XX_25519_ChaChaPoly_SHA256` — legacy PSK placement (≈psk0), the only PSK form
noise-java supports. Security rationale (PSK-gated mutual auth + ephemeral forward
secrecy) is in ADR-002. Wrapped behind `cc.grepon.portage.transport` Kotlin so the crypto
choice stays swappable.

## To re-verify / update

```sh
git clone https://github.com/rweather/noise-java && cd noise-java
git checkout 49377b6dfc6a1e75740bce2318118291a57c0d6e
diff -r src/main/java/com/southernstorm \
        <this-repo>/core-transport/src/main/java/com/southernstorm   # expect no diff
```
A security-reviewer pass on this vendored tree + the Kotlin glue is REQUIRED before release.
