# Four-phase companion checkpoint — October 13, 2026

Based on fetched main `cdf64d1214927237bb49bb24cb23a3b69a12eb0a`, version
1.1-dev/code 2. Work is on `chatgpt/four-phase-reliability-2026-10-13`.
The owner merges main. No new app was installed, and no signer, pairing,
Keystore key, Room schema or existing ciphertext was replaced.

## Implemented prerequisites

- Automatic OS authentication starts only when resumed and focused. Retained
  attempt state survives rotation, cancellation does not loop, and Try again
  remains available. Permission/picker return and foreground callbacks retain
  lock/epoch boundaries. AndroidX prompt callbacks attach at Activity creation.
- Screen capture protection now remains active throughout the protected app,
  including when additional phone processing restrictions are off.
- The existing encrypted configuration persists the additional Local Only/HIPAA
  processing preference. Older configurations default safely. This processing
  preference does not disable screen capture protection or weaken queued privacy.
- Polling restarts after either vault-open path and configuration changes,
  respects workspace generations, and recovers after Stop and lock/reopen.
  Foreground 30-second/5-minute/manual polling and OS-managed notification work
  have distinct, truthful labels. Manual has no periodic foreground polling;
  explicit startup/configuration checks can still occur.

## Actual validation

Full local unit run: 65 cases, 64 passed, one external Node fixture skipped.
A fresh isolated desktop Core/SQLite pinned-HTTPS fixture subsequently passed
that interoperability case with no skips: combined coverage 65 passing cases.
These are two retained runs, not a fabricated single 65-pass run.
Lint: zero errors, 42 warnings. App and instrumented-test APKs built.
Final instrumented sources compiled and test APK signature verified with the
existing debug certificate. Instrumented tests were **not executed** locally.

App SHA-256: `72ea4604a2b47940e32756c157c3f50449381598435370ff5517c0b73e4589b0`.
Test APK SHA-256: `62c00417cd4de2c689bb04ac95002105923858b86f7a37115047e2257ce7c7a1`.
Private local receipt/logs are in the desktop checkout's ignored
`Validation/four-phase-2026-10-13/`. No owner data or pairing secrets are published.

## Pending work and safety constraints

QR bootstrap and 20/30-minute/Off session options remain pending. The existing
nonexportable vault key has a fixed 300-second authentication authorization.
Changing a UI timer cannot extend it; no longer-session choices should be exposed
until a reviewed, recovery-tested compatible key/session design exists.
Preserve the current key and ciphertext throughout that work.

QR work must authenticate the desktop certificate before sending an expiring
bootstrap token, atomically consume the grant, bind owner-approved scopes and
chat identity, and preserve legacy manual pairing. No QR endpoint/package was
added at this checkpoint. Device cold launch/cancel/rotation/Home/picker,
recents/screenshot, LAN/VPN, notifications, battery and signed upgrade acceptance
remain required. Historical Android acceptance does not validate this changed APK.

Shared execution plan is in desktop `docs/FOUR_PHASE_PROGRESS_2026-10-13.md`.
Complete Phase 1 hosted/device verification, then Phases 2, 3 and 4 without
duplicate features or changing the owner installation prematurely.
