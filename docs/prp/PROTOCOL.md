# PROTOCOL.md — `portage` pairing + transfer wire format (v4)

Scope: one sender (`portage-send`, old phone), one receiver (`portage-recv`, new phone),
same LAN, no cloud, no relay. One transfer session at a time.

## 1. Discovery vs. trust — two different jobs

- **QR code = the trust anchor.** It is an out-of-band visual channel a network
  adversary cannot read. It carries secret material (a one-time PSK) and session
  identity. Everything cryptographic hangs off it.
- **mDNS/NSD = convenience discovery only.** Any LAN host can spoof mDNS, so it carries
  *zero* trust: it only answers "what IP:port do I try?". Authentication is end-to-end
  in the handshake regardless of how the address was found. Worst case for spoofed
  discovery is a failed handshake (DoS), never a compromised one.

Defense of this split (vs. mDNS-only with a verification PIN): a PIN short enough to
type is brute-forceable by an online attacker racing the handshake unless you add a
PAKE (SPAKE2+). The QR lets us carry a full-strength 256-bit secret with the same UX
gesture, so no PAKE is needed. mDNS-only is kept as a *fallback discovery* path when the
QR-embedded IP is stale (DHCP churn between display and scan).

### QR payload

URI form: `portage1:<base64url(CBOR)>` with fields:

| field | type | meaning |
|---|---|---|
| `v` | uint | protocol version, `4` |
| `psk` | bytes(32) | one-time pre-shared key, CSPRNG |
| `sid` | bytes(16) | session id (public; also used to match the mDNS instance) |
| `ip` | array of text | sender's current addresses, best-effort hints |
| `port` | uint | sender's listening TCP port |
| `exp` | uint | unix seconds; QR invalid after (default now+120 s) |

Sender registers NSD service `_portage._tcp` with instance name `portage-<hex(sid[0..4])>`
while the transfer screen is open. Receiver tries `ip:port` from the QR first, then
browses mDNS for the matching instance. The sender app sets `FLAG_SECURE` on the QR
screen and regenerates `psk`/`sid` every time the screen is (re)shown.

## 2. Handshake — Noise `NoisePSK_XX`

**Pattern:** `NoisePSK_XX_25519_ChaChaPoly_SHA256`, exactly one ciphersuite per protocol
version. No negotiation exists on the wire, so there is nothing to downgrade.

> **Amended 2026-06-10 (ADR-002, after the library spike):** originally specified as
> `Noise_XXpsk3_…`, but no audited JVM/Kotlin Noise library implements the modern `pskN`
> placement modifiers. We use vendored noise-java's **legacy PSK** form (`NoisePSK_XX`,
> PSK mixed at the start ≈ `psk0`). The authentication property is identical: completing
> the handshake is impossible without the QR PSK, regardless of placement. The "mixed at
> position 3" wording below is the conceptual goal; the implementation mixes the PSK at
> the start. Verified by `core-transport`'s NoiseLoopbackTest (match → channel; mismatch →
> no channel).

**Why XXpsk3 and not something else:**
- *vs. plain `XX`:* XX alone authenticates "whoever you first met" (TOFU) — a same-LAN
  MITM present at handshake time wins. Mixing the QR PSK at position 3 (`psk3`) makes
  completing the handshake impossible without QR possession. The PSK is the mutual
  authenticator; the static keys ride along.
- *vs. `NNpsk0` (PSK-only, no statics):* would be sufficient for a one-shot transfer and
  is the approved **fallback if the chosen library lacks psk-modified XX**. XXpsk3 is
  preferred because the exchanged static keys give us (a) resume after an app restart
  *without* re-scanning, by re-handshaking `KKpsk0`-style against remembered statics
  bound to the same `sid`, and (b) a "remembered device" identity for repeat transfers.
- *vs. TLS 1.3 (raw public keys or self-signed pinning):* drags in a PKI-shaped API to
  then disable most of it; cert pinning across two ephemeral apps is more code and more
  footguns than a fixed Noise pattern. Noise gives us exactly the four properties we
  need (mutual auth from PSK, forward secrecy from ephemerals, AEAD transport, tiny
  surface) with nothing to misconfigure.
- *vs. libsodium `crypto_box` keyed off QR keys:* doable but hand-rolls the transcript,
  rekeying, and nonce discipline that Noise specifies. Use a vetted Noise implementation
  instead of reinventing one with sodium primitives.

**Roles:** receiver = Noise initiator (it dials the TCP connection); sender = responder.

**Prologue** (mixed into the handshake hash; any mismatch fails the handshake):
`"portage" || v || sid || "recv->send"`. This binds protocol version and session id into
the transcript — a spliced or cross-session handshake cannot complete.

**Message flow (XX, psk3):**
```
initiator(recv) → responder(send):  e
responder       → initiator:        e, ee, s, es        (sender static, encrypted)
initiator       → responder:        s, se, psk          (receiver static; PSK mixed)
```
After message 3 both sides hold transport keys. What authenticates whom:
- *Sender to receiver:* sender's handshake message 2 + first transport frame only
  decrypt/authenticate if sender derived keys mixing the same PSK ⇒ sender holds QR
  material (it generated it).
- *Receiver to sender:* handshake message 3's AEAD tag fails unless the receiver knows
  the PSK ⇒ receiver scanned this QR. The sender accepts **exactly one** successful
  handshake per `sid`, then marks the PSK consumed (replay/second-suitor lockout).

Forward secrecy: per-session ephemerals; compromise of a static key later does not
decrypt a recorded session (PSK compromise + recorded traffic also fails: `ee`/`es`/`se`
DH results are mixed in).

## 3. Framing

- Wire = TCP. After the handshake, every frame is one Noise transport message:
  `u16 BE length || ciphertext` (Noise max plaintext 65 519 B respected).
- One application message per Noise payload. Application messages are CBOR maps with an
  integer `t` (type) field. Unknown map keys MUST be ignored (forward compat); unknown
  `t` ⇒ respond `ITEM_ACK{status:SKIPPED}` where applicable or close with `ERROR`.
- Binary fields (notably ITEM_DATA `bytes`) are **definite-length CBOR byte strings**
  (major type 2), NOT arrays of integers — an array encoding ~doubles text payloads and a
  60 KiB chunk would overflow the 65 519 B plaintext budget / 65 535 B frame cap.
- Rekey (`Noise` `REKEY`) every 1 GiB of payload per direction.

## 4. Message sequence (manifest-first)

```
recv→send  HELLO        {t:0, app_version, os_fingerprint}
send→recv  MANIFEST     {t:1, items:[ItemMeta…], sender_name, totals}
recv→send  SELECT       {t:2, want:[item_id…], resume:[{item_id, offset}…]}
loop per selected item (sender-driven, sequential):
  send→recv ITEM_BEGIN  {t:3, item_id, kind, size, chunk_size, meta}
  send→recv ITEM_DATA   {t:4, item_id, seq, bytes}        × ⌈size/chunk⌉
  send→recv ITEM_END    {t:5, item_id, sha256}
  recv→send ITEM_ACK    {t:6, item_id, status, detail?}
send→recv  BATCH_END    {t:7, sent:[…], summary}
recv→send  BATCH_ACK    {t:8, results:[{item_id, status, detail?}…]}
close
```

`ItemMeta = {item_id (u32), kind (tstr: "contacts.vcf" | "calendar.ics" | "calllog" |
"sms" | "mms" | "inventory" | "apk" | "settings" | "wallpaper" | "sound.selection" |
"sound.file" | "app.backup.relay" | "user.file" | …), tier (0|1),
size, sha256, display_name, group}`.

> The `contacts.vcf` kind is vCard 3.0 plus Portage extension fields where Android exposes
> useful device-local metadata that standard vCard does not carry. `X-PORTAGE-STARRED:1`
> preserves the user's favorite/starred contact flag when Portage inserts a fresh raw contact.
> Standard inline `PHOTO;ENCODING=b` carries thumbnails up to 256 KiB each; sender-side retained
> photo bytes are capped at 8 MiB per export. Existing matching contacts are deduplicated rather
> than mutated solely to change the starred bit or photo. Standard `NICKNAME`, `BDAY`, and typed
> `URL` properties preserve the corresponding Android raw-contact rows. Website types map all
> Android categories; `URL;TYPE=CUSTOM` uses a bounded Base64url `X-PORTAGE-LABEL` parameter to
> preserve the provider's custom label. Repeated standard `CATEGORIES` fields carry visible group
> names; receiver-side local group IDs are found or created by exact title because source account
> and group IDs are device-specific.

> The `wallpaper` kind (PRP-02) is the first binary-blob payload (a home/lock wallpaper
> image, not structured text). Its item stream is a one-line JSON `WallpaperHeader`
> (surface + advisory format/bounds) followed by the raw image bytes. The receiver
> re-derives format from magic bytes and runs a bounds-only decode gate before setting the
> wallpaper — see THREAT_MODEL §2 row 10.

> The `app.backup.relay` kind (PRP-06) carries an OPAQUE, user-exported app backup
> (Signal/Molly message history, Aegis 2FA vault) device-to-device. Its item stream is a
> one-line JSON `RelayHeader` (typed app id + advisory package/note, re-validated on the
> receiver) followed by the app-encrypted bytes — which portage NEVER decrypts, parses, or
> interprets. portage is a COURIER for a file the USER made, not a backup engine: this is
> categorically NOT the forbidden `seedvault.blob` below (PRP-06 §2 — "portage must never
> be the thing that creates the backup"). The per-item byte cap is raised FOR THIS KIND
> ONLY (app backups exceed the 64 MiB Tier-0 ceiling); the raise never touches the Tier-0
> PII paths.

> The `user.file` kind carries only files explicitly selected through Android's Storage
> Access Framework. Each item is a bounded JSON header plus opaque bytes and is saved through
> MediaStore under `Downloads/Portage`. It is capped at 512 MiB per file, 64 files and 4 GiB
> per transfer. Protocol `v=2` is required because `ItemKind` is encoded as an enum: a v1 peer
> cannot decode an unknown enum value, so mixed versions fail during QR validation rather than
> failing after pairing.

> The `sound.file` kind carries one active custom default-sound file for ringtone,
> notification, or alarm. Each item is a bounded JSON header (role + display name + MIME +
> length) followed by opaque audio bytes. The receiver stores it through MediaStore under
> `Ringtones/Portage`, marks the appropriate ringtone/notification/alarm media flag, and
> records the new local URI in a transfer-scoped remap table. The later `sound.selection`
> item uses that remap by role; if the file was absent or failed to register, the role is
> skipped rather than writing a dangling sender URI. Protocol `v=3` is required for the new
> enum value.

> The `mms` kind carries MMS inbox/sent history as JSON-lines `MmsRecord` rows: message
> metadata, address rows, and text/binary parts. It is separate from `sms` because MMS uses
> Android's MMS tables (`content://mms`, `addr`, `part`) and has different partial-failure
> behavior. Sender export is capped to the receiver's standard 64 MiB Tier-0 item limit and
> streams one MMS record at a time; large binary parts / records that would exceed the bounded
> export are skipped rather than creating an item the receiver will refuse. Receiver writes
> require the same transient default-SMS role as SMS. RCS state, carrier/service state, drafts,
> pending sends, and original thread ids remain out of scope. Protocol `v=4` is required for
> the new enum value.

> No `seedvault.blob` kind in v4: couriering a Seedvault file would imply app-data
> transfer, which the Seedvault division of labor explicitly excludes (PRP §2,
> DEVILS_ADVOCATE Q5). Reconsider only behind a future protocol bump with explicit UX copy.

- **Integrity:** every byte already rides inside AEAD frames (in-flight integrity);
  `ITEM_END.sha256` is the *at-rest* check over the assembled item — it catches
  receiver-side staging corruption and validates resumed items end-to-end.
- **Resume:** receiver persists staged partials keyed by `(sid_origin, item_id,
  bytes_received)`. On a new session (re-scan or remembered-statics re-handshake), it
  offers `resume` offsets in SELECT; sender seeks. The final `sha256` must still match;
  on mismatch the receiver discards the staging file and re-requests from offset 0.
- **Apply vs. receive:** `ITEM_ACK` reports *receipt+verification*. Application of
  settings/contacts happens receiver-side after staging; apply-results go in
  `BATCH_ACK.results` (and the receiver's own done-screen). A settings key that fails to
  apply is a per-key line item in the summary, never a transport error.

## 5. Failure semantics

- A failed item (hash mismatch, write error, unknown kind, oversize) yields
  `ITEM_ACK{status≠OK}`; the sender logs it and proceeds to the next item.
  **Nothing short of a transport/AEAD failure aborts the batch.**
- AEAD authentication failure, handshake timeout (10 s), or malformed frame ⇒ immediate
  close (fail-closed; no retry within the same `sid` unless the handshake never
  completed).
- Idle timeout 30 s with `PING {t:9}` keepalive.
- The receiver enforces its own limits regardless of manifest claims: max item size,
  max item count, staging-dir quota, CBOR depth/size caps. Receiver never trusts
  `kind`-implied semantics beyond its compiled catalog (see THREAT_MODEL §malicious-peer).

## 6. Notes for the build agent

- The PRP's "signed manifest" is intentionally dropped: inside a mutually-authenticated
  AEAD channel, a signature adds no authentication the channel doesn't already provide.
  Re-introduce only if manifests ever become standalone exportable artifacts.
- Library order of preference: a maintained Kotlin/JVM Noise implementation supporting
  psk modifiers; if none clears review, fall back to `NNpsk0` over the best-supported
  pattern set, keeping this document's auth analysis (PSK is the authenticator either way).
- Chunk size 64 KiB default; tune on-device.

## Confidence + open questions

- Handshake/auth design: **high confidence** — standard Noise usage; the only bespoke
  parts are the prologue binding and PSK-consumption rule, both simple.
- Open: actual Noise library availability/quality on Android is the deciding factor
  between XXpsk3 and NNpsk0 (VERIFY_FIRST #8). Open: NSD on GOS across profiles and with
  the GOS Network permission denied to one app — must produce a clear in-app error, not
  a silent hang (VERIFY_FIRST #6).
