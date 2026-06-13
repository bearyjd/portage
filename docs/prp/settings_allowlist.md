# settings_allowlist — classification (safety-critical)

Companion to `settings-catalog/` (`settings_allowlist.kt`). This is the human-reviewable
source of truth; the `.kt` file is generated/checked against it.

## Columns

- **Key** — `Settings.{System,Secure,Global}` constant.
- **Class** — `SAFE` (synced by default), `RISKY` (opt-in, off by default),
  `DEVICE_SPECIFIC` (never synced).
- **Reach** — how the receiver writes it:
  - `T0-system` = `Settings.System`, writable with user-granted `WRITE_SETTINGS`
    ("Modify system settings" special access) — **no Shizuku**.
  - `T1-grant` = `Settings.Secure`/`Global`, needs `WRITE_SECURE_SETTINGS` once via
    Shizuku self-grant, then direct.
  - `T1-shell` = must go through a live Shizuku shell (role/overlay/pm), not a settings
    write.
  - `n/a` = excluded.
- **Validate** — receiver-side clamp/check before apply.

> Default sync set = **SAFE only**. The receiver applies a key only if it is in this
> compiled table; the sender's manifest cannot introduce keys. Every applied value is
> range/enum-validated regardless of class.

## Display / UI behavior

| Key | Namespace | Class | Reach | Reason / Validate |
|---|---|---|---|---|
| `FONT_SCALE` | System | SAFE | T0-system | Accessibility-relevant cosmetic. Clamp 0.85–1.30. |
| `SCREEN_OFF_TIMEOUT` | System | SAFE | T0-system | Behavioral. Clamp to known enum (15s–30m). |
| `SCREEN_BRIGHTNESS_MODE` | System | SAFE | T0-system | Auto vs manual; behavioral. Enum {0,1}. |
| `SCREEN_BRIGHTNESS` | System | **DEVICE_SPECIFIC** | n/a | Panel-dependent backlight scale; same int = different nits across models. The classic "looks SAFE, isn't" trap. |
| `ACCELEROMETER_ROTATION` | System | SAFE | T0-system | Auto-rotate on/off. Enum {0,1}. |
| `Secure UI_NIGHT_MODE` | Secure | SAFE | T1-grant | Dark theme pref. Enum {0,1,2}. |
| `Global` font/display density (`display_density_forced`) | Global | RISKY | T1-grant | Density override surprises layout; opt-in. Validate against device dpi buckets. |

## Sound / haptics / notification behavior

| Key | Namespace | Class | Reach | Reason / Validate |
|---|---|---|---|---|
| `Global ZEN_MODE` config / DND schedule | Global | RISKY | T1-grant | Behavioral but can silence the new phone unexpectedly; opt-in. |
| `System HAPTIC_FEEDBACK_ENABLED` | System | SAFE | T0-system | Behavioral. Enum {0,1}. |
| `Secure vibrate-on-ring` / `System VIBRATE_WHEN_RINGING` | mixed | SAFE | T0/T1 | Behavioral. Enum {0,1}. |
| `System DTMF/SOUND_EFFECTS_ENABLED` | System | SAFE | T0-system | Cosmetic UI sounds. Enum {0,1}. |
| Ring/notification/alarm **volumes** (`VOLUME_*`) | System | RISKY | T0-system | Stream indices differ; a copied alarm volume of 0 is a missed-alarm hazard. Opt-in, never default. |
| Ringtone / notification **sound URIs** | System | **DEVICE_SPECIFIC** | n/a | Content URIs point at on-device media that won't exist on the new phone → silent or crash. Exclude; offer "pick again" UX instead. |

## Accessibility

| Key | Namespace | Class | Reach | Reason / Validate |
|---|---|---|---|---|
| `Secure ACCESSIBILITY_DISPLAY_MAGNIFICATION_*` | Secure | SAFE | T1-grant | Genuine accessibility need; cosmetic risk low. |
| `Secure ACCESSIBILITY_DISPLAY_DALTONIZER` + `_ENABLED` | Secure | SAFE | T1-grant | Color-correction mode + toggle. Enum-validate. |
| `Secure ACCESSIBILITY_CAPTIONING_*` | Secure | SAFE | T1-grant | Caption styling prefs. |
| `Secure ENABLED_ACCESSIBILITY_SERVICES` | Secure | **DEVICE_SPECIFIC** | n/a | Lists component names of *installed* a11y services; copying enables service references that may not exist or silently grants powerful a11y access to a service the user hasn't re-vetted. Dangerous trap — exclude. |
| `Secure HIGH_TEXT_CONTRAST_ENABLED` | Secure | SAFE | T1-grant | Cosmetic a11y. Enum {0,1}. |
| `Secure ACCESSIBILITY_LARGE_POINTER_ICON` | Secure | SAFE | T1-grant | Cosmetic a11y. |

## Input / keyboard / locale

| Key | Namespace | Class | Reach | Reason / Validate |
|---|---|---|---|---|
| `System TIME_12_24` | System | SAFE | T0-system | "12"/"24". Enum. |
| `Secure DEFAULT_INPUT_METHOD` | Secure | RISKY | T1-grant | Points at an IME component; only valid if the same keyboard app is installed (depends on Tier-0 inventory restore). Apply *after* app install, validate component resolves, else skip. |
| `System TEXT_AUTO_REPLACE / AUTO_CAPS / AUTO_PUNCTUATE` | System | SAFE | T0-system | Legacy text behavior toggles. Enum. |
| `Secure SHOW_IME_WITH_HARD_KEYBOARD` | Secure | SAFE | T1-grant | Behavioral. Enum. |
| System locale / `Global` locale | — | RISKY | T1-grant | Locale is usually set in first-boot setup; re-applying can fight the wizard. Opt-in, apply post-setup. |

## Animation / developer-options subset

| Key | Namespace | Class | Reach | Reason / Validate |
|---|---|---|---|---|
| `Global WINDOW_ANIMATION_SCALE` | Global | SAFE | T1-grant | Cosmetic. Clamp {0,0.5,1}. |
| `Global TRANSITION_ANIMATION_SCALE` | Global | SAFE | T1-grant | Cosmetic. Clamp. |
| `Global ANIMATOR_DURATION_SCALE` | Global | SAFE | T1-grant | Cosmetic. Clamp. |
| `Global ADB_ENABLED` / `Secure ... adb_wifi` | Global | **DEVICE_SPECIFIC** | n/a | Security state. Never auto-enable debugging on the new device. Exclude hard. |
| `Global DEVELOPMENT_SETTINGS_ENABLED` | Global | RISKY | T1-grant | Convenience for a dev user; off by default; explicit opt-in only. |
| `Global STAY_ON_WHILE_PLUGGED_IN` | Global | RISKY | T1-grant | Battery/burn-in implications; opt-in. |

## System UI / navigation

| Key | Namespace | Class | Reach | Reason / Validate |
|---|---|---|---|---|
| Navigation mode (gesture vs 3-button) | overlay (not a settings key) | RISKY | T1-shell | Stored as an enabled overlay, set via `cmd overlay enable-exclusive`; needs live Shizuku. Opt-in. Verify V7. |
| `Secure SYSUI_QS_TILES` (quick-settings layout/order) | Secure | RISKY | T1-grant | References tile specs that may not all exist on the new build (esp. GOS-specific tiles) → missing tiles. Filter to tiles that resolve; opt-in. |
| `Secure DOZE_*` / always-on display | Secure | RISKY | T1-grant | Behavioral + battery; opt-in. |

## Identity

| Key | Namespace | Class | Reach | Reason / Validate |
|---|---|---|---|---|
| `Global DEVICE_NAME` (`device_name`) | Global | SAFE | T1-grant | User-chosen device / Bluetooth display name (e.g. "Pixel 10 Pro Fold"). A preference, not hardware-bound → migration continuity. `StringPattern` bounded length 1–256; rejects blank, control chars, newlines (single-line display string; no lock-screen/BT/UI injection). Treat as hostile input (THREAT_MODEL §10). |

## Connectivity (mostly excluded)

| Key | Namespace | Class | Reach | Reason / Validate |
|---|---|---|---|---|
| Wi-Fi networks / saved PSKs | not in Settings provider | **DEVICE_SPECIFIC** | n/a | Held in privileged WifiConfigStore, not a settings key; unreadable without system app. Out of scope — do not promise. |
| Bluetooth pairings | privileged store | **DEVICE_SPECIFIC** | n/a | Same. Re-pair manually. |
| `Global AIRPLANE_MODE_ON` / radio state | Global | **DEVICE_SPECIFIC** | n/a | Live device state, not a preference. Exclude. |
| `Global AUTO_TIME` / `AUTO_TIME_ZONE` | Global | SAFE | T1-grant | Behavioral preference (use network time). Enum {0,1}. |

## GrapheneOS specials (drop or research-flag — do NOT promise)

| Key | Class | Reach | Reason |
|---|---|---|---|
| Per-app Network permission (INTERNET) | RISKY (research) | T1-shell | GOS-extended permission; *maybe* `pm grant/revoke`. Gate on V7; if it works, opt-in per app. |
| Per-app Sensors permission (OTHER_SENSORS) | RISKY (research) | T1-shell | Same as above. |
| Exploit-protection toggles (hardened_malloc opt-out, MTE, DCL, WebView JIT) | **DEVICE_SPECIFIC** | n/a | GOS-private package state, no stable shell interface. **Drop entirely**, mention in README as manual. |
| LTE-only / 2G toggle, Wi-Fi/BT timeout, USB-when-locked, panic settings | **DEVICE_SPECIFIC** | n/a | GOS security/hardware state; some explicitly excluded even by Seedvault. Exclude. |

## Reach summary for the build agent

- **No Shizuku needed at all** (T0-system, just user-granted "Modify system settings"):
  font scale, screen timeout, brightness *mode*, auto-rotate, haptics, UI sounds, time
  format, basic text toggles. **This is a shippable settings-sync slice without Tier 1.**
  The PRP under-scoped Tier 0 here — promote these.
- **Shizuku one-shot grant** (T1-grant): the `Secure`/`Global` cosmetic + accessibility +
  animation keys.
- **Live Shizuku shell** (T1-shell): nav mode, GOS per-app toggles — the genuinely
  fragile, opt-in, verify-first features.

## "Looks SAFE but isn't" — the trap list (re-state for reviewers)

1. `SCREEN_BRIGHTNESS` (absolute) — panel-relative; copy the *mode*, not the value.
2. Ringtone/notification **sound URIs** — dangling content URIs.
3. `ENABLED_ACCESSIBILITY_SERVICES` — silently re-grants powerful a11y access.
4. `DEFAULT_INPUT_METHOD` / `SYSUI_QS_TILES` — reference components/tiles that may not
   exist on the target; apply-after-install + resolve-or-skip.
5. `ADB_ENABLED` family — never copy security/debug state to a fresh device.
6. Stream **volumes** — a copied 0 is a silent-alarm safety bug.

## Confidence + open questions

- Classifications: **high confidence** on the SAFE/DEVICE_SPECIFIC calls (grounded in
  what each key *means*, not in measured behavior). Medium on which `Secure`/`Global`
  keys are *additionally* system-uid-gated even with `WRITE_SECURE_SETTINGS` — settled by
  the audit in VERIFY_FIRST #2.
- Open: exact current GOS key names for nav mode and per-app toggles (V7). Open: whether
  `display_density_forced` survives sanely across different native densities — keep RISKY
  until measured.
