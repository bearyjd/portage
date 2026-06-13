# THREAT_MODEL.md — `portage` v1

## 1. Adversary model

In scope: an **active same-LAN adversary** — controls another host on the network (or
the AP itself), can sniff, ARP-spoof, mDNS-spoof, inject/RST TCP, and race connections.
Has **no view of either phone's screen** and no physical access.

Out of scope (documented, not defended): a compromised OS on either phone; an adversary
with camera view of the QR; coercion of the user; GOS supply chain.

Assets: contacts/calendar/SMS/call-log content, app inventory (fingerprintable),
Bluetooth device roster — names + MAC addresses (fingerprintable; no link keys, which are
controller-bound and never carried), settings values, APK payloads (integrity, not
confidentiality), the Seedvault blob if couriered (already Seedvault-encrypted; we protect
it anyway), and the **relayed app-backup blob** (PRP-06: a user-exported, app-encrypted
Signal/Molly/Aegis backup — a full message history / 2FA vault — the HIGHEST-sensitivity
opaque secret we carry; app-encrypted with a USER-only passphrase portage never sees, and
double-wrapped under the Noise transport in transit).

## 2. Attacks and the property that stops each

| # | Attack | What stops it (named property) | Residual |
|---|---|---|---|
| 1 | Passive sniffing of transfer | ChaCha20-Poly1305 AEAD transport keyed from the Noise handshake; handshake itself exposes only ephemeral public keys (statics are sent encrypted under `ee`-derived keys in XX) | Traffic analysis: timing + approximate sizes + endpoints visible (see §3) |
| 2 | Active MITM (ARP spoof, rogue AP, interposed proxy) | **psk3 mutual authentication**: completing either side of `Noise_XXpsk3` requires the 32-byte QR PSK mixed into the chaining key. An interposer cannot produce a valid handshake-msg-3 tag toward the sender, nor valid msg-2/transport frames toward the receiver. Connection dies before any payload | None beyond DoS |
| 3 | mDNS/NSD spoofing (attacker advertises a fake `portage` instance) | **Discovery carries no trust**: receiver may connect to the attacker, but the handshake fails without the PSK. The receiver treats handshake failure as "wrong device / retry", and may try the next discovered candidate | DoS; UX confusion if attacker floods instances — cap candidates, prefer QR-embedded IPs |
| 4 | Handshake replay (recorded msgs re-sent) | Fresh ephemerals per session: a replayed initiator message yields keys the replayer can't compute (no `e` private key). **PSK consumption**: sender accepts exactly one completed handshake per `sid`, then invalidates the PSK; `exp` (120 s TTL) bounds the window | None meaningful |
| 5 | Transport-frame replay / reorder / splice | AEAD nonce sequence per direction — replayed or reordered frames fail authentication and close the session. Cross-session splice blocked by per-session keys + `sid` in the **prologue** (transcript binding) | None |
| 6 | Downgrade (ciphersuite or version) | **No negotiation exists**: one suite per protocol version; version is in the QR payload and in the prologue. A tampered version claim ⇒ prologue mismatch ⇒ handshake failure | User on old app version must update — fail-closed by design |
| 7 | Second suitor (attacker races the real receiver to connect first) | Without the PSK the race is unwinnable (auth fails). If the *real* receiver completes first, PSK consumption locks everyone else out; sender UI shows the paired peer before data flows | Attacker can DoS by occupying the single listener slot with slow handshakes — 10 s handshake timeout, one-strike close |
| 8 | QR interception | Out-of-band visual channel; network adversary cannot see it. App-level: `FLAG_SECURE` on the QR screen (blocks screenshot/cast capture), 120 s TTL, regenerate on every screen show, PSK single-use | A human with eyes on the screen during the window wins — accepted residual, documented |
| 9 | Malicious *receiver* (valid QR, hostile device) | The QR **is** the consent ceremony: scanning happens because the sender's owner displayed it. Sender additionally shows the selected item list before streaming | A tricked user who shows the QR to an attacker's phone leaks what they then approve — UX must keep the item list visible |
| 10 | Malicious *sender* / poisoned payloads toward the receiver | **Receiver-side allowlist enforcement**: settings are applied only if the key exists in the receiver's *compiled* catalog, with per-key type/range validation — sender's manifest cannot expand that set. APKs install only through user-confirmed `PackageInstaller` flows (Tier 0) or the explicit batch screen (Tier 1); inventory entries are data, not code. All staged items are written to app-private staging with **generated filenames** — manifest names are display-only, killing path traversal. CBOR decoding has depth/size caps. **Binary image payloads (`wallpaper`, PRP-02)** pass a pre-write gate (`WallpaperApplyProvider`): a magic-byte MIME allowlist (PNG/JPEG/WebP — declared `format` re-verified, not trusted), a `MAX_WALLPAPER_BYTES` cap, and a **bounds-only decode** (`inJustDecodeBounds`) with a `MAX_PIXELS` ceiling so a decompression bomb is rejected before any bitmap is allocated; the typed `WallpaperSurface` enum derives the `FLAG_*` so a payload can't redirect the surface | A hostile sender can still send garbage *values* for SAFE keys (e.g., font_scale 0.01) — mitigated by per-key range clamps in the catalog. Wallpaper EXIF GPS is sent as-is (accepted residual §3, to one's own device over the AEAD channel) |
| 11 | DoS (SYN flood, junk connects, oversized frames) | Listener exists only while the transfer screen is open; accepts one connection; 10 s handshake deadline; `u16` frame cap; staging quota | LAN DoS is always winnable by the adversary; we only guarantee fail-closed |
| 12 | Metadata exposure via mDNS | Instance name derives from random `sid`, not the device name; service registered only during an active transfer screen | The *existence* of a portage transfer is visible on the LAN; option: "QR-only mode" toggle disabling NSD |
| 13 | Malicious sender abuses the **app-backup relay** (PRP-06): a redirect to a hostile package, a covert app-data path, or a parser exploit via the opaque blob | **Courier, not backup**: only a USER-PICKED file enters — portage has NO code path that reads app-internal data or PRODUCES a backup (the `seedvault.blob` deciding test, PRP-06 §2). The blob is **never decrypted, parsed, or sniffed** — read for length + sha256 only, exactly the transport's existing opaque-item contract. **Re-link redirection blocked**: the target package is derived from the typed `RelayApp` enum and the advisory `targetPackage` is re-validated against it + the package-name regex (`RelayHeader.sanitizedOrNull`) before any intent — a hostile sender can't point the re-link at an arbitrary app or smuggle a scheme (the hardened `InstallAction` precedent). The restore note/name are control-stripped + length-bounded. The **passphrase never touches portage**. The per-item cap is raised for THIS KIND ONLY and stays finite (`maxBytesByKind`), so no unbounded write; manifest size + sha agreement still hold | The blob is a user secret portage carries but cannot open; if the user forgets the passphrase they cannot restore (human residual, §3). A brief user-visible copy on the receiver (handoff to the target app) is the user's own file in a user location — documented residual (§3) |

## 3. Residual risks (explicit)

1. **Traffic analysis:** sizes/timing reveal roughly how much of each category moved.
   Padding is not worth the complexity for v1 — documented as accepted.
2. **Trusted-Wi-Fi Shizuku autostart** (if the user enables it) means an ADB-privileged
   binder is alive whenever on that SSID — a standing privilege surface unrelated to
   portage but adjacent to our setup instructions. The README must not recommend enabling
   it without explaining this.
3. **Both apps need the GOS Network permission**; a user who denies it gets silent
   socket failures unless we detect and message it (VERIFY_FIRST #6).
4. **Receiver staging contents** (contacts etc.) sit in app-private storage until
   applied; deleted on completion/abort. GOS FBE protects at rest; we add
   delete-on-finish hygiene, nothing stronger claimed.
5. **Sender-side consent granularity** is the item list, not per-field; a sender owner
   who approves "contacts" sends all contacts.
6. **Relayed app-backup passphrase** (PRP-06): portage shows a reminder to bring the
   app's backup passphrase but NEVER captures, stores, transmits, or logs it — the
   passphrase stays a user-only secret re-typed into the target app. A user who forgets it
   cannot restore; portage cannot help, by design. Mirrors the "secret the user must carry
   out of band" shape of the QR-PSK residual (§1).
7. **Relayed-backup user-visible copy** (PRP-06): handing the opaque file to the target
   app needs a brief copy in a user-accessible (app-scoped external) location under a
   GENERATED name. That copy is the user's own file in their chosen location; the opaque
   bytes are never logged or interpreted. A longer-lived copy is an accepted, documented
   residual — prefer delete-after-import.
8. **Persisted SAF read grant for a relay pick** (PRP-06): so a pick survives the
   export-then-return-to-portage workflow (incl. activity recreation), the sender takes a
   persistable read grant on the user-picked backup Uri and releases it on remove / reset /
   successful ship. If the process is killed mid-flow before any release, that read grant can
   orphan across sessions. Accepted, bounded residual: it is the user's OWN read-only file,
   never the passphrase, releasable, and bounded by Android's per-app persisted-grant cap —
   same "on-device process compromise out of scope" boundary as the QR-PSK residual (§1).
   CLOSED (PR #42): `MainActivity.sweepOrphanedRelayGrantsOnce()` releases all persisted SAF
   grants on a cold process start (guarded once-per-process so a config-change recreation keeps
   live picks' grants), bounding the orphan window to a single process lifetime.

## 4. Properties summary (what the design *guarantees*)

- Confidentiality + integrity of all payload against any network position: AEAD with
  keys derivable only with QR possession.
- Mutual authentication anchored in a 256-bit out-of-band secret (no PIN brute-force
  surface, no TOFU window, no PKI).
- Forward secrecy per session (ephemeral DH mixed even though a PSK exists).
- Fail-closed on every cryptographic anomaly; fail-*open* never.
- Receiver sovereignty: nothing in the protocol can make the receiver write state
  outside its compiled catalog and user-checked selections.

## Confidence + open questions

- The crypto-property claims are **high confidence** *conditional on* using a vetted
  Noise implementation correctly (the usual place this class of design dies is library
  misuse — mandate the security-reviewer pass on the `core-transport` module).
- Open: whether `FLAG_SECURE` suffices against GOS screen-recording paths (expected
  yes); whether NSD instance names leak the device hostname via reverse records on some
  resolvers (verify with a packet capture during V-runs).
