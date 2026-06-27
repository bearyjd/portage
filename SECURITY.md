# Security policy

portage handles high-sensitivity personal data and includes a narrowly scoped local ADB
privilege bridge. Please do not open a public issue for a suspected vulnerability.

Report vulnerabilities through
[GitHub private vulnerability reporting](https://github.com/bearyjd/portage/security/advisories/new).
Include affected versions, reproduction steps, impact, and any proposed mitigation. Do
not include real contacts, messages, keys, pairing payloads, or other personal data.

The maintainers will acknowledge a report as soon as practical, validate it, coordinate
a fix and release, and credit the reporter unless anonymity is requested. There is
currently no bug bounty or guaranteed response SLA.

## Supported versions

Until the first stable release, only the latest commit on `main` receives security fixes.

## Scope

High-priority reports include:

- bypasses of the QR-anchored Noise authentication or payload integrity checks;
- data exposure through logs, storage, intents, components, or the local network;
- privilege escalation or expansion beyond the typed `adb-bridge` operations;
- sender/Play-flavor contamination with the privileged bridge;
- path traversal, unsafe payload parsing, or receiver allowlist bypasses.

The documented adversary model and accepted residual risks are in
[`docs/prp/THREAT_MODEL.md`](docs/prp/THREAT_MODEL.md).
