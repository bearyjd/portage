# U3a — item-status microcopy catalog (Swiss-terse)

Status: DRAFT copy catalog, ready to lift into the U3a implementation (per-item failure
surfacing on the receiver Done screen). No code change in this document. Voice sources:
`app-recv/.../ui/TransferScreen.kt`, `app-send/.../ui/SendScreens.kt`.

## Voice rules (derived from the shipped screens — keep new copy inside these)

- **Verdict = one or two tracked-caps words**, `labelSmall`, error color for failures
  (precedent: `phaseWord()` — WAITING / RECEIVING / APPLYING / DONE / FAILED).
- **Section headers = `LABEL · N NOUN(S)`**, tracked caps, primary color (precedent:
  `INSTALL · 3 APPS`, `RE-PAIR · 2 DEVICES`).
- **Body copy: short declarative sentences.** Second person. The metaphor is *carrying*
  (“carried”, “moved”, “left behind”, “didn’t carry”). Name the actor honestly (“this
  phone”, “your old phone”, “portage” — lowercase). Never blame the user. If there is a
  next step, say it; if there isn’t, say that plainly. No exclamation marks. One em-dash
  pivot per sentence at most.
- **Honesty over reassurance** (precedent: “Bluetooth pairings can’t move between
  phones”, “N refused by the new phone — its summary has the why”).

## Terminal vs transient (the U3a split)

| class | statuses | meaning |
|---|---|---|
| **transient** | `HASH_MISMATCH`, `WRITE_ERROR` | sending the same item again can succeed |
| **terminal** | `SKIPPED`, `UNKNOWN_KIND`, `OVERSIZE` | the same item gets the same verdict; only a changed condition (update, role, smaller item) changes the answer |

Note: U3a has **no in-place retry** (payloads are deleted post-apply; the Noise session is
one-shot). “Send again” in this copy always means *run another transfer* — never promise a
retry button.

## Per-status catalog

The verdict word is the right-aligned label (like `phaseWord`); the reason is one
`bodyMedium` sentence under the item name. The wire `detail` string (engineer-grade,
e.g. “staged bytes do not match the advertised hash”) stays as an optional third line,
verbatim — the reason is the human layer above it, not a replacement.

| `ItemStatus` | verdict | reason string | class |
|---|---|---|---|
| `OK` | `MOVED` | *(none — moved items need no explanation)* | — |
| `SKIPPED` | `SKIPPED` | This phone chose to leave it. | terminal |
| `HASH_MISMATCH` | `DAMAGED` | Didn’t arrive intact — sending it again usually fixes this. | transient |
| `WRITE_ERROR` | `NOT SAVED` | This phone couldn’t save it — worth sending again. | transient |
| `UNKNOWN_KIND` | `UNKNOWN` | This phone’s portage doesn’t know this kind of item — update portage here, then send again. | terminal |
| `OVERSIZE` | `TOO BIG` | Too big to carry — this phone caps what one item can bring. | terminal |

Grounding (production emit sites, so the copy stays honest):

- `SKIPPED` is always a *deliberate* receiver decision — already installed
  (`ApkApplyProvider`), settings key not in the allowlist (`SettingsProviders`), needs the
  default-SMS role (`Sms/MmsProviders`), not an allowlisted image (`WallpaperProviders`),
  not requested / not delivered (`ItemStreamReceiver`). “Chose to leave it” is accurate for
  all of them; the detail line carries the specific why.
- `HASH_MISMATCH` = staged bytes vs advertised hash, or an ITEM_END id mismatch — transit
  damage or desync; a fresh transfer genuinely tends to succeed.
- `WRITE_ERROR` = free-space, staging, provider, or platform write failures — retry-worthy
  but not guaranteed, hence “worth sending again”, not “will work”.
- `UNKNOWN_KIND` = no apply handler (version skew) or kind/manifest disagreement — the
  user-actionable case is the update; the desync case still can’t apply, same message.
- `OVERSIZE` = per-item cap, aggregate byte budgets, size/manifest disagreement, or more
  bytes than advertised — all “this phone caps what one item can bring”.

## Done-screen grouping (replaces the lumped “N left behind”)

Two sections, same pattern as INSTALL/RESTORED/RE-PAIR, transient first (it has an action):

- **`TRY AGAIN · N ITEMS`** (transient) — footer caption:
  > Sending these again usually works — start another transfer from your old phone.
- **`LEFT BEHIND · N ITEMS`** (terminal) — footer caption:
  > Sending these again won’t change the answer — each row says why.

A section with zero items is absent, not empty (existing DoneScreen convention). When
everything moved, neither section renders and the summary stands alone — no “all good!”
filler.

## Empty-state copy

- **Nothing moved, everything failed** (`moved == 0`, results non-empty): keep the big
  Swiss numeral — `0` + “things moved” is honest — and add one summary line where
  “N left behind” sits today:
  > Nothing made it over this time — the rows below say why.
- **Nothing was selected to carry** (`moved == 0`, no results — defensive):
  > Nothing was picked to carry. Go back and choose what should come over.
- **Sender Done, `sent == 0`**: no new copy needed — “0 things carried over” plus the
  existing “N refused by the new phone — its summary has the why” already covers it.

## Ready-to-lift Kotlin shape (mirrors `phaseWord` / `friendlyPermissionName`)

```kotlin
/** Right-aligned verdict word for a terminal item result (Done screen, U3a). */
internal fun statusWord(status: ItemStatus): String = when (status) {
    ItemStatus.OK -> "MOVED"
    ItemStatus.SKIPPED -> "SKIPPED"
    ItemStatus.HASH_MISMATCH -> "DAMAGED"
    ItemStatus.WRITE_ERROR -> "NOT SAVED"
    ItemStatus.UNKNOWN_KIND -> "UNKNOWN"
    ItemStatus.OVERSIZE -> "TOO BIG"
}

/** One-sentence human reason; null for OK (moved items need no explanation). */
internal fun statusReason(status: ItemStatus): String? = when (status) {
    ItemStatus.OK -> null
    ItemStatus.SKIPPED -> "This phone chose to leave it."
    ItemStatus.HASH_MISMATCH -> "Didn’t arrive intact — sending it again usually fixes this."
    ItemStatus.WRITE_ERROR -> "This phone couldn’t save it — worth sending again."
    ItemStatus.UNKNOWN_KIND ->
        "This phone’s portage doesn’t know this kind of item — update portage here, then send again."
    ItemStatus.OVERSIZE -> "Too big to carry — this phone caps what one item can bring."
}

/** Terminal = re-sending the same item yields the same verdict. */
internal fun isTerminal(status: ItemStatus): Boolean = when (status) {
    ItemStatus.OK, ItemStatus.HASH_MISMATCH, ItemStatus.WRITE_ERROR -> false
    ItemStatus.SKIPPED, ItemStatus.UNKNOWN_KIND, ItemStatus.OVERSIZE -> true
}
```

Out of scope here (stays with U3a proper): retaining `List<ItemResult>` in
`ReceiverState.Done`, the row composable, and any rework of the wire `detail` strings.
