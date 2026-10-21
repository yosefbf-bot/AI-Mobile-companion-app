# Finished local release interoperability candidate — October 20, 2026

Companion source base: `56413e55a42b2d110319ac982488ec744d5d39be`.
Candidate: **1.2.6-dev / version code 9**, original application ID and signing
configuration. This is a local private release candidate, not a public production
certification. No production Kotlin, schema, dependency, authentication, privacy
or protocol implementation changes are included in this step.

The existing full validation path retains its Android build, unit, lint and all
twelve actual API34 OS-authentication emulator cases. Its two external-desktop
unit fixtures can still be explicitly skipped there. A new, separately required
`windows-desktop-interop` job must execute those same two existing client tests
against the actual desktop Core and encrypted SQLite store over pinned HTTPS on
a disposable Windows runner. Missing, failed, cancelled or skipped integration
execution fails `ci-gate` on the full validation path. The administrative-only
path remains narrowly allowlisted; workflow/test/build changes require full
validation. Feature-branch pushes do not create an additional workflow run.

`.github/desktop-interop.json` records the canonical desktop repository, full
immutable commit and version. The coordinated target is desktop main
`452800210a33e34b1d18a648d1288562d9061d56` / **3.0.8-dev**. Its reviewed source
and actual Windows PR acceptance have passed; the coordinator must also preserve
full exact-head main/Pages acceptance before publishing this companion batch. Both actual checkout commits and
trees, desktop/Android versions, Windows/Node identity and exact JUnit case IDs
are included in the receipt. Tracked source changes or untracked source inputs
fail the harness before or after execution.

The job verifies the Gradle wrapper checksum, installs the desktop's locked npm
dependencies without lifecycle scripts, and compiles Android unit classes before
creating the three-minute bootstrap invitation. It uses the unchanged
`DesktopGatewayInteropTest` and `BootstrapDesktopInteropTest`: chat replay/history,
phone audio chunk delivery, evidence transfer, source-linked follow-ups, reviewed
internal drafts, notification acknowledgment and single-use pairing replay
rejection. Both exact cases must pass with **zero failures and zero skips**.

All records, keys and pairing material are newly generated synthetic fixtures in
the hosted runner's temporary directory. The gateway binds only `127.0.0.1`;
normal certificate creation, verification, client pinning and scope enforcement
are retained. No owner profile, model, production pairing, signer or installation
is read or replaced. The fixture stops its optional speech/draft timer; it tests
audio transport and storage, not transcription. `/calc 2+2` exercises the actual
local calculator and chat protocol, not AI inference. An unexpected provider call
fails the fixture; no model response is manufactured. Actual packaged Windows
model inference remains a separate desktop acceptance gate.

Only sanitized receipts, minimized JUnit XML and bounded first-failure diagnostics
are uploaded. The parser
checks exact case identities, counts, age and zero skips, rejects fixture-secret
markers, and strips arbitrary test output/properties. The receipt hashes the
original XML. On failure, a redacted Gradle tail and the failed JUnit identities,
counters, exception types and bounded message/stack details are saved before
temporary cleanup. Actual fixture secrets, URI/header/authentication values and
runner paths are removed; unsafe or oversized diagnostic material fails closed.
Fixture startup failures expose fixed stage codes, not raw exceptions.
Raw process logs, pairing links, encrypted fixture stores and
temporary files are not artifacts. Cleanup targets only this invocation's owned
processes and resolved temporary directory.

Current local checks: **42 Python parser/gate/redactor/Git-range cases passed**, zero
failures. No local Gradle, build, emulator, app installation or signing was performed. Fresh exact-head hosted validation, final independent source review and
coordinated desktop main acceptance are **pending** at this source checkpoint. No current
actual interoperability execution is claimed by these authored gates.

Physical phone/OEM upgrade, camera scanning, LAN, microphone, notifications,
reboot and live Outlook/WhatsApp account acceptance remain deferred. Notifications
are best effort and require an awake/reachable PC. Correspondence reviews
selected Outlook snapshots and approved WhatsApp cache; it is not a live Inbox or
reply watcher. Speaker attribution remains reviewed and experimental. Direct
Android-to-cloud AI remains cancelled.
