# Open questions (cross-plan tracking)

Append-only. One section per plan; check items off as the spike/decision resolves them.

## PRP-01 Wi-Fi saved networks + passphrases — 2026-06-12 · RESOLVED 2026-07-31 (#123)

All three resolved on-device (GrapheneOS **Android 17 / SDK 37**, Pixel 10 Pro Fold). Evidence:
`docs/prp/features/SPIKE-RESULTS-2026-07-31.md` §7. **Overall verdict: NO-GO for credential
parity** — see PRP-01 §0.

- [x] Is `/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml` readable at shell uid (2000)? — **NO. The NO-GO trigger fired.** Permission denied at uid 2000 on the file, on the legacy `/data/misc/wifi/` path, and on the containing directory. The Tier-1 read path does not exist; passphrases stay root-only, as the 2026-06-12 spike predicted, now confirmed on A17.
- [x] Which restore path works without per-network prompts and shows networks as "saved": `WifiNetworkSuggestion` (a) vs `cmd wifi add-network` (b)? — **(b) VERIFIED** at shell uid: `cmd wifi add-network <ssid> open` added a network that appeared immediately in `cmd wifi list-networks` as saved, with no per-network prompt; `forget-network` removed it cleanly. (a) remains untested — it needs app code, and is moot while the read side is NO-GO. Note (b) needs shell uid, so it is a RECEIVER-side path only (the receiver has the bridge).
- [x] Does the SENDER read of saved Wi-Fi need shell uid? — **YES, and it is therefore unavailable.** All three read paths are closed to `app-send`: the config file is denied even at shell uid; `cmd wifi list-networks` requires shell uid, which `app-send` must never have (no-escalation CI assert); and `NETWORK_SETTINGS` — which gates privileged enumeration via the app API — is `protectionLevel: signature`, unreachable for a non-platform-signed app. `ACCESS_WIFI_STATE` is `normal` but grants Wi-Fi *state*, not saved-network enumeration.
