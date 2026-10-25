# Private AI Android companion instructions

## ECC development workflow defaults — 2026-10-25

When native `ecc@ecc` is available, select its guidance from ordinary task intent:
`planner` for substantial/cross-component changes; `tdd-workflow` for meaningful
behavior/regression changes; `verification-loop` before significant completion;
`code-reviewer` for substantial/security-sensitive work; `security-review` for
authentication, pairing, credentials, files, PHI and external connections;
`e2e-testing` for changed UI flows; `build-error-resolver` for build/type failures.
Planner/reviewer/resolver are installed `agents/` guidance, not Claude configs.
Load only relevant guidance. ECC is optional development tooling, never an app
dependency or release prerequisite. Existing security/CI requirements take precedence.

Main agent implements/tests; at most one additional helper investigates or reviews
read-only. No helper edits, nested delegation, duplicate work or repeated reviews
without an unresolved issue. Preserve ChatGPT sign-in and chosen model/effort;
no API billing, optional memory/learning jobs, background loops or new integrations.
Use meaningful focused regression tests, not blanket coverage or timed full suites.

Use the checked-in Gradle wrapper and JDK 17: `.\gradlew.bat --no-daemon
assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest` for the relevant
full check; filter focused unit tests with `testDebugUnitTest --tests <test-class>`.
Keep the actual CI instrumentation commands and isolated Windows HTTPS interop
fixture requirements; never fabricate credentials or convert a fixture skip to pass.
Changes to pairing/reconnect need pinned TLS, same-pairing replay identity,
offline/partial-sync handling and desktop compatibility checks. Changed UI needs
keyboard/composer, dark-mode and applicable navigation/lock tests. Preserve OS
authentication, Keystore, original signer, encrypted drafts, additive migrations,
generic restricted notifications and the PC-mediated AI architecture. Never bypass
MFA/lock/TLS or send/delete messages to demonstrate a workflow. Report physical-phone
LAN/VPN, microphone/camera, background delivery and upgrade checks as untested when
the environment is unavailable; emulator/build results are separate evidence.

GitHub is authoritative: `https://github.com/yosefbf-bot/AI-Mobile-companion-app`.
Before every implementation task, fetch and verify `origin/main`, the active
branch, worktree changes and version in `app/build.gradle.kts`. Preserve local
work. Use a separate `chatgpt/*` branch and a review pull request; the owner merges
main. Recheck main before publication. Do not claim an unchecked local copy is
current. If connector access fails, ordinary authorized Git access may still work.

Read `AI_HANDOFF.md` and `docs/SHARED_DESKTOP_WORKFLOWS.md`. The desktop repository
is `https://github.com/yosefbf-bot/Private-AI-OG-google-version`; its
`docs/ANDROID_COMPANION_CONTRACT.md` defines the wire contract. Inspect both latest
architectures before changing the protocol; do not replace them with a sample
bridge script or historical export.

Preserve the application ID, signing compatibility, existing records and additive
Room/content migrations. Never use destructive migration or plaintext encryption
fallback to pass tests. Full vault access requires Android OS authentication and
Keystore encryption. Never add a production test unlock, application PIN bypass,
static shared pairing key or accept-all TLS. The optional background worker holds
only the independent restricted notification credential and generic metadata.

Real mode uses authenticated, certificate-verified desktop responses. Demo is
explicitly labeled and isolated. Missing telemetry, models, dates, owners and
clinical findings remain unknown. Photos are review evidence unless a separately
implemented and validated image-analysis capability is available. Operational
completion is not clinical resolution; internal drafts are not sent messages.

Persist request identity and privacy requirements before network attempts. Keep
retries bound to the same device pairing and input; stricter current policy must
not weaken prior privacy or change an already attempted replay. Failures and
partial synchronization must be visible, without fabricated success.

Run the Gradle wrapper build, unit tests and lint, plus applicable real HTTPS
interoperability and Android instrumented tests. Report skips/failures honestly.
Compilation and Robolectric do not establish physical-phone Keystore, camera,
microphone, notification, LAN or signing acceptance. Update the handoff with exact
commit/test/artifact scope. Keep `.env`, pairing links, signing keys, databases,
user evidence, SDKs, caches and APKs out of source control. The explicitly named
synthetic gateway test certificate is a public fixture, never a production key.

## Current assignment authorization — 2026-10-14

For this four-phase assignment, the owner explicitly authorized Codex to merge
completed review pull requests after their current exact-head GitHub tests and
checks pass, then synchronize the local main and development checkouts safely.
This supersedes older owner-only merge instructions for this assignment only.
Continue using separate `chatgpt/*` branches and review PRs; do not bypass failed
or pending checks, force-push, discard unfinished work, or remove unique history.
The owner also authorized removing obsolete build/version files and fully merged
branches after verifying they are no longer needed. Preserve active work, current
accepted delivery, recovery/reference evidence, credentials, signing keys, models
and pairings. This permission does not authorize replacing installed production
apps/data or weakening release, privacy, resource or security gates.


## Standing feature-addition preference — 2026-10-14

Before implementing a new feature, check GitHub for suitable free open-source components. Evaluate license, maintenance, security, offline/privacy behavior and compatibility. Reuse existing capabilities instead of adding duplicates. Prefer focused integration of trustworthy components when it saves work; use custom code when available projects do not fit. Explain selected components and meaningful feature/function changes. This does not override release or permission gates.

## Standing CI spending preference - 2026-10-18

The owner requests lower GitHub Actions usage. Read `docs/CI_COST_CONTROLS.md`
before workflow changes. Batch coherent, locally checked work before pushing;
avoid repeated unchanged builds and duplicate branch-push/PR validation.
Keep PR/main validation and manual full acceptance. Require the current `ci-gate`
and its selected checks to succeed. Runtime/build/dependency/test/workflow/contract
and unknown changes still require full validation, including actual Windows or
Android emulator acceptance. Only the explicit administrative-document path may
skip runtime jobs, and those skips must never be described as executions.
Do not use skip-CI commit directives or weaken checks to conceal a failure.
Use trusted dependency-only caching; preserve accepted release/current rollback
payloads and evidence before temporary artifacts expire. Do not raise billing
budgets, enable paid external services or replace installed apps to save CI cost.
