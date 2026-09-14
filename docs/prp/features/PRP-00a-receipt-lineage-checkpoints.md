# PR 0a — Receipt Phases, Lineage, and Durable Checkpoints

## Goal

Make transfer progress truthful and recoverable before later migration features build on it. A received payload must never be reported as durably applied, two logical occurrences must never share replay state, and resuming a move must require a secret established by the original authenticated peers.

This plan implements the `PR 0a` unit approved in `turnkey-migration-audit-2026-09-13.md`.

## Approach

1. Introduce protocol v6 models for migration lineage, canonical occurrence IDs, item wire-schema versions, phased receipts, lineage bootstrap/resume, and authenticated cancellation.
2. Add a shared single-writer lineage/checkpoint component with an atomic snapshot store, strict compare-and-set transitions, injected time/randomness, tombstones, and exact expiry behavior.
3. Generate a 16-byte lineage only for an explicit new move and a distinct 16-byte occurrence ID for every selected manifest entry. Persist prepared identity and staging metadata so restarts keep the same move.
4. Establish a separate 32-byte resume credential inside the first authenticated channel. Resume handshakes derive their Noise PSK from both a fresh QR PSK and the stored credential, and bind protocol version, pairing mode, and session ID into the transcript.
5. Validate lineage and every occurrence ID before the receiver exposes manifest data to UI and again through validated checkpoint-key construction before store access.
6. Persist receiver transitions in order: `PREPARED → RECEIVED_VERIFIED → APPLYING → APPLIED_DURABLE`, with typed failure states. Startup reconciliation converts interrupted `APPLYING` to `UNKNOWN_INTERRUPTED` unless later provider-specific proof exists.
7. Treat `ITEM_ACK` only as verified receipt. Only a valid final `BATCH_ACK` may report `APPLIED_DURABLE`; a dropped final acknowledgement produces `UNKNOWN_INTERRUPTED` on the sender.
8. Tombstone and delete local resume secrets immediately on finish, cancel, expiry, or a new move. While connected, cancellation records remote deletion only after authenticated acknowledgement.
9. Update the protocol documentation and add wire goldens plus deterministic restart, expiry, malformed-input, isolation, and interruption tests.

## Areas and Files

- `core-model`: v6 manifest, pairing, identity, receipt, failure, lineage, and cancel message types plus pure validation.
- New `core-lineage` module: state reducer, checkpoint keys, atomic repository, clock/random seams, purge and restart reconciliation.
- `core-transport`: v6 codec registration/goldens, resume-PSK derivation, transcript binding, and initial/resume handshake wiring.
- `app-send`: explicit new/resume lifecycle, lineage-aware manifest staging, durable prepared state, final-ack truth.
- `app-recv`: pre-UI validation, persistent receive/apply transitions, staged-byte reconciliation, lineage bootstrap/resume, cancel acknowledgement.
- `docs/prp/PROTOCOL.md` and architecture/data codemaps.

## Acceptance Criteria

- Protocol version is 6; v5/v6 pairing is rejected before network use.
- New-move lineage generation consumes exactly 16 `SecureRandom` bytes. One hundred moves are unique; retry/relaunch does not generate a replacement lineage.
- The initial authenticated session creates one distinct 32-byte resume credential. Resume requires both it and a fresh QR secret; a QR alone, wrong credential, wrong lineage, or pairing-mode tampering fails closed.
- Two byte-identical selected files receive distinct canonical lowercase 32-hex occurrence IDs and distinct checkpoint keys.
- Malformed, uppercase, wrong-length, or duplicate occurrence IDs are rejected before checklist/UI construction, checkpoint access, staging, role acquisition, or provider side effects.
- Checkpoint keys include lineage, occurrence, kind, item wire-schema version, declared size, and canonical SHA-256. Cross-lineage reads and invalid transitions fail closed.
- Atomic-store reopen preserves active lineage and legal checkpoints; corrupt or unknown snapshots surface an error instead of silently starting empty.
- At each receive/apply boundary, repeated restart tests never promote `RECEIVED_VERIFIED` or `APPLYING` to `APPLIED_DURABLE`; interrupted apply becomes `UNKNOWN_INTERRUPTED`.
- Staged bytes avoid retransmission only after exact key, size, and hash revalidation. `APPLIED_DURABLE` is not skippable without provider-specific proof; PR 0a defaults to replay/confirmation when proof is unavailable.
- Dropping `BATCH_ACK` yields `UNKNOWN_INTERRUPTED` for every receipt-verified item in 20/20 deterministic runs.
- Connected cancel requires `CANCEL_ACK` before claiming peer deletion. Disconnected cancel remains local-only. Purge occurs at exactly 30 days of no authenticated activity, not one millisecond earlier.
- JVM tests, both Android flavor unit tests/builds, static privilege gates, and wire-format tests pass.

## Out of Scope

- Provider-specific durable evidence/readback implementations beyond the conservative “not proven” default; those land with the relevant replay-fidelity PRs.
- Byte-offset streaming resume. PR 0a resumes prepared or fully verified occurrences; partial files restart from byte zero.
- Backup extraction hardening, Keystore wrapping, and the full no-backup audit assigned to PR 0b. New lineage secrets nevertheless live under `noBackupFilesDir` from inception.
- Export completeness changes, provider fidelity work, Home Map, launcher restore, app unification, and UI redesign.

## Risks and Controls

- The vendored Noise API accepts one PSK. Resume derives that PSK with HKDF-SHA256 from the fresh QR PSK and stored resume credential; this construction receives an independent security review and wire tests.
- Provider mutation and checkpoint persistence cannot be one transaction. The design records `APPLYING` first and reports ambiguity honestly as `UNKNOWN_INTERRUPTED`.
- Whole-file atomic snapshots require a single writer and bounded data. The store enforces its format/version and item bounds; future multi-process access must migrate the repository boundary to SQLite.
- Persisted secrets are sensitive. They are app-private, no-backup, zeroed in memory where ownership permits, time-bounded, and removed on terminal lifecycle actions.
