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


## October 14 hosted authentication acceptance correction

Phase 1 production source is unchanged by this correction. Both exact-head
Android runs (38506962144 and 38506959005) built, passed unit/lint and retained
artifacts; each executed four API 34 emulator cases with three pass/one failure.
The failed cancellation fixture pressed Back once while the PIN keyboard was
open. Both original logcats show that Back hid the input method; the original
system credential window remained. Neither trace shows a second authentication
session before the assertion. This did not demonstrate a production prompt loop.

The test now targets the actual system credential window, cancels it using its
Cancel action or a bounded second Back while that same window remains, verifies
it disappears and reaches a terminal callback, then preserves the no-reprompt,
rotation, locked-data, Retry and three real PIN/Keystore/Notes/relock assertions.
No production authentication, key, encryption, session duration or signer changes.

Fresh focused unlock-state tests: ten passed, zero failures/skips. Lint remains
zero errors/42 warnings; full unit result was restored from unchanged Gradle inputs
(cache), not a newly executed 65-case run. Corrected instrumented APK compilation,
packaging and signature verification passed. Production APK remains unchanged.
Test APK SHA-256: `ef21a88d0b00e75476450d752e29696a5582de9cb11dfb3522b0faab1e9e3867`.
Fresh exact-head hosted device execution is required; local compilation is not a
passing device result. Sanitized original failures/correction receipts remain in
the desktop ignored `Validation/four-phase-2026-10-13/`.

## October 14 actual Home transition correction

Both `13694876` API 34 runs (38531126312 and 38531121066) passed cancellation,
rotation and two real OS PIN/Keystore/Notes/relock cycles. Each then failed the
third credential-field assertion: the test relaunched 197/309 milliseconds after
Home, and both continuous logs show PAUSED then RESUMED without STOPPED. No new
Android authentication request was created. Draining currently idle main work
did not wait for the actual Home transition to deliver `onStop`, which rearms
automatic authentication for a new background entry.

The test now waits at most ten seconds after real Home for the actual stopped
ActivityScenario state, asserts the workspace remains locked and logs that stop
before the existing real launch. It does not force a lifecycle transition, tap
Retry for return, bypass a callback or weaken the three real authentication cycles.
Production authentication, keys, privacy flags, session duration and signer are
unchanged. The real cancellation and rotation assertions remain mandatory.

Fresh focused state execution passed all ten cases with zero failures, errors or
skips. Lint has zero errors/42 warnings; instrumented sources compiled and the test
APK packaged in the same successful 1m 15s Gradle invocation. The new test APK
signature verifies with the existing debug certificate; SHA-256 is
`36ef03ac81ceb8ce65c7b01104be22445cdc0ea8f078315c7b109dfaf8c8e9bb`.
The production APK remains unchanged. The full unit suite was not repeated for
this test-only barrier; both failed hosted heads had 64 passes and the one external
fixture skip, lint zero errors/75 warnings, and successful retained artifacts.
Fresh exact-head hosted device acceptance remains required. No local installation
or physical-device execution occurred. Sanitized paired lifecycle traces, XML,
signature and correction receipt are retained as `android-home-stop-barrier-*`
in the desktop ignored validation directory.
