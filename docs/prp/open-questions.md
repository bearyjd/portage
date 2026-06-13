# Open questions (cross-plan tracking)

Append-only. One section per plan; check items off as the spike/decision resolves them.

## PRP-01 Wi-Fi saved networks + passphrases — 2026-06-12
- [ ] Is `/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml` readable at shell uid (2000) on GOS A16, or `system`-only? — Determines whether the Tier-1 read path exists at all (NO-GO trigger).
- [ ] Which restore path works on A16 without per-network prompts and shows networks as "saved": `WifiNetworkSuggestion` (a) vs `cmd wifi add-network` (b)? — Restore reliability is the entire user value; prefer (a) for update-resilience.
- [ ] Does the SENDER read of saved Wi-Fi need shell uid? — app-send must link NO privilege stack (no-escalation CI assert); a privileged sender read is a scope expansion that must be resolved before Phase 0.
