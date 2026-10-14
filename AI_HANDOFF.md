## October14 current Phase4 checkpoint

Sourcebranch chatgpt/companion-pairing-2026-10-14 starts at verified main7e817069.
Phase1 PR3 is merged; both exact-head device runs passed4/4 cases with actual
OSPIN/Keystore cycles, and main38570691148 passed. Prior failure/pending entries
below are historical and superseded.

Currentcandidate1.2-dev/code3 adds on-device CameraX/ZXingQR bootstrap, verified
TLS-before-token, encrypted config commit after real authenticatedstatus, and
central five-minute elapsed session tied to actual authenticationcallback.
17 focusedbootstrap +10 lease/decoder tests and2 actual Kotlin/Core HTTPS cases
passed. Original failures and corrections retained. Finalfullbuild/unit/lint/
signer +hosted six actualdevice tests are pending, not completed by compilation.
Two new instrumented cases cover camera-permissiondenial/manualfallback and real
300-second access plus actualOS-key expiry. No owner app/data/key/signer changed;
longer/Off choices disabled without an approved key migration.

Read docs/COMPANION_PAIRING_2026-10-14.md and current desktoptracker/resumeevidence.
Owner permits exact-head-green reviewedPR merge then safe main synchronization;
do not bypass pending/failedchecks or replaceinstalledapps/data. At1% five-hour
remaining save/report/pause; prior one-time resumefired and mustnotrecur.

---

## October 14 latest actual device result — unresolved

Exacthead13694876PR3 failed both API34 runs38531126312/38531121066:
three of four casespassed; credentialwindowfieldmissing attestline154 in
unlockNavigateAndRelock. Causeundetermined; prior IME-only Back issue is not
established as this newfailure. Build,unit64pass/1fixture-skip,lint0errors/75warnings
and artifactuploadpassed. Keepdraft; no completed device acceptance claim.
Evidence is in desktopValidation/four-phase-2026-10-13/android-hosted-13694876-inspection.json
andmatching sanitizedlogs/XML. DesktopPhase3 backends are saved uncommitted;
full642casespassed, UI/transportunfinished. Pausedat1%; one-time resumealreadyfired.
Ownerapps/data/keys/signernotchanged. Fetchmains/readdesktoptrackerbeforecontinuing.

## October 14 authentication acceptance fixture correction

Read the October 14 section in [the four-phase tracker](docs/FOUR_PHASE_PROGRESS_2026-10-13.md).
Both API34 runs failed because one Back hid the PIN keyboard without cancelling
the original system dialog; no second prompt was shown. Corrected test performs
actual OS cancellation and retains all security assertions and real Keystore cycles.
Ten focused state cases passed; instrumented APK compiled/signature passed. Full
unchanged unit output was cached. Production APK/data/key/signer unchanged.
Fresh hosted device acceptance is pending. Owner merges main.

## October 13 four-phase source checkpoint

Read [the companion checkpoint](docs/FOUR_PHASE_PROGRESS_2026-10-13.md) first.
Automatic OS prompting, fixed capture protection, persisted processing preference
and lifecycle-safe polling are implemented on the new review branch.
Combined 65 local cases pass, lint has zero errors/42 warnings, both APKs build,
and final instrumented sources compile. Actual device execution is pending.
Do not claim QR/longer sessions complete or transfer older device acceptance.
No installation, key rotation, signer or pairing change. The owner merges main.
Shared plan is in the desktop tracker. Resume this thread once October 14 at
3:25 a.m. America/New_York after the 1% checkpoint; fetch both mains first.

## October 12 final hosted validation result

Read [the completed validation checkpoint](docs/FINAL_VALIDATION_2026-10-12.md)
and its [sanitized exact-head receipt](docs/final-validation-2026-10-12.json).
Both Android `47e3b4a` hosted runs passed all four instrumented tests, including
three real OS PIN/Keystore unlock/Notes/relock cycles and lifecycle cleanup.
All 51 local Android tests passed. Desktop `f7777a5` functional checks passed;
accepted Windows binary code remains `6e915aa`. Production binaries are unchanged.
All four hosted runs failed only their mandatory artifact uploads on GitHub quota.
Earlier sections are historical checkpoints; their pending Android status is
superseded by this result. Check current PR checks for later documentation heads.
The owner merges main. Keep review branches until successful integration.

## October 12 follow-up: complete real activity cleanup on main

Both ca1d6ea hosted runs (38307718223 and 38307723181) passed three actual OS
PIN/Keystore unlock/Dashboard/Notes/relock cycles. Both still failed the test during
framework teardown: repeatOnLifecycle removed observers on the instrumentation
thread when Compose 1.7.2 withTestCoroutines called TestScope.runTest to drain
cancellations. This is a failed device acceptance result, despite passing test-body
assertions; the earlier wrong-thread composition crash did not recur.

The follow-up explicitly closes the real ActivityScenario and drains the queued
lifecycle cleanup on Android main in @After, before Compose's final test-scope
cleanup. Exceptions and assertions are preserved. The screen-only regression now
uses the same lifecycle-aware collection as MainActivity. No production code,
runtime version, APK, authentication or encryption change. Local validation of
this follow-up passed all 51 tests (zero failures/errors/skips), real desktop
pinned-HTTPS interoperability, lint (zero errors/73 warnings), app and test APK
builds. Production APK SHA-256 remains 4b3ea024d6d748a382682587834f1d5bf7168c7f83812b2c786828c79fb91425.
Evidence: desktop ignored Validation/assistant-first-2026-10-07/android-lifecycle-cleanup.log
and the matching Gradle XML. Fresh hosted device verification remains required.

Desktop f7777a5 passed both exact-head regression/dependency/build checks; only
artifact retention failed on account quota. All 26 approved old archives remain
verified in recovery and removed remotely. Current instructions leave main merging
to the owner. Preserve both draft PRs until remaining checks pass.

## October 12 original device failure identified: test dispatcher

Diagnostic PR run 38274513926 reproduced the crash on the third authentication
cycle. Its first exception is CalledFromWrongThreadException: the Compose test
frame clock resumed Android view work on DefaultDispatcher-worker-4 after the
repository emitted vaultReady from its IO scope. The SlotTable disposal exception
was secondary. The push run passed all three cycles, explaining the intermittency.

The narrow correction supplies StandardTestDispatcher to both Android Compose
rules and the Notes screen regression; only the Android test configuration gains
kotlinx-coroutines-test. Production code, runtime dependencies, UI authentication,
Keystore and data remain unchanged. The exact installed Compose 1.7.2 source
supports this dispatcher override. Google's guidance explains the unconfined
versus queued test behavior: https://developer.android.com/develop/ui/compose/testing/migrate-v2
No Compose/runtime upgrade or relaxed assertion is required. The real OS PIN and
three unlock/Dashboard/Notes/relock cycles remain mandatory.

Local validation after this correction passed all 51 tests with no failures,
errors or skips, including the real synthetic Node Core/SQLite pinned-HTTPS
interoperability and ten Notes disposal/restoration cycles with background state
emission. Lint has zero errors/73 warnings; both app and test APKs build. The app
APK remains byte-for-byte unchanged (SHA-256 4b3ea024d6d748a382682587834f1d5bf7168c7f83812b2c786828c79fb91425).
Evidence: desktop ignored Validation/assistant-first-2026-10-07/android-dispatcher-final-2.log
and the matching Gradle XML reports. Two earlier local runs exposed missing waits
in this new regression; they remain recorded as failed runs. The corrected test
waits for the draft-save effect and each UI transition without removing assertions.
Fresh hosted device results remain required. Current workspace instructions leave
main merging to the owner; preserve the review branch until integration.

## October 12 intermittent device lifecycle investigation

Source 4a1dd87 passed all four device tests in push run 38239708086, while PR run
38239713135 crashed in Compose SlotTable disposal. This is unresolved, not a
passing acceptance claim. The OS-authentication/Notes/relock case now repeats
three times and logs the original test exception before rule teardown. CI keeps
continuous synthetic-device logcat to preserve the first error. Production code,
authentication, dependencies and existing data are unchanged by this diagnostic
commit. The approved 26 old CI archives were backed up, verified and removed;
GitHub still reports artifact quota pending recalculation. Do not merge a known
failing head. Current workspace instructions leave main merging to the owner.

## October 12 device acceptance: Dashboard assertion correction

Hosted run 38238022399 compiled, passed unit/lint and executed four device tests. Keeping the emulator awake resolved the missing UI hierarchy; three tests passed. The remaining real OS-authentication test successfully unlocked Keystore and reached Dashboard, then expected an obsolete label. It now asserts the current explicit warning that no recorded alerts does not establish complete monitoring. Production UI/security is unchanged. Fresh exact-head device acceptance and artifact upload remain required; full remote artifact storage is still blocked pending cleanup authorization.

## October 12 hosted acceptance follow-up

The owner authorized merging both reviewed mains and deleting superseded branches only after completion, preserving unique work. Source 28a6fe1 passed hosted compilation/unit/lint, but device acceptance had two UI failures (no Compose hierarchy), not a passing emulator result. The next run keeps the disposable emulator awake, exposes failure diagnostics even when artifact storage is full, and explicitly opens Dashboard after the new Chat default. Production authentication/security is unchanged. Full hosted device acceptance remains pending.

# Current in-progress simplification

Read [the October 11 checkpoint](docs/ASSISTANT_FIRST_PROGRESS_2026-10-08.md) first. It covers simpler navigation, phone recording/PC transcription, persistent connection status, dark mode and 50 passing tests. The owner revoked the old 1% stop rule. Older acceptance reports do not establish physical-phone acceptance of these changes.

# Android companion development handoff

## Current integration

GitHub is authoritative. This update starts from Android main
`7028ec6d131ffaaf9e03d2f22b3550d7a38ddf3c` and desktop main
`c269a18eec4ea5b4e347e3520be1cd4e9bd88876`. Both were fetched again before
publication. Work uses `chatgpt/shared-companion-workflows-2026-10-05` in both
repositories. The owner merges main. Android remains the original application ID
`com.aistudio.privateai.kq7zwa`, now development version **1.1-dev**, version code **2**.
The desktop remains **3.0.1-dev**. Source changes do not update installed applications.

Read `AGENTS.md`, `docs/SHARED_DESKTOP_WORKFLOWS.md`,
`docs/ANDROID_VALIDATION_PROGRESS.md`, and the desktop
`docs/ANDROID_COMPANION_CONTRACT.md` before editing. The desktop is
`https://github.com/yosefbf-bot/Private-AI-OG-google-version`; the companion is
`https://github.com/yosefbf-bot/AI-Mobile-companion-app`.

## Implemented changes

- Real desktop-owned chat/history, explicitly shared PC conversations, status,
  report-job and clinical-alert metadata replace simulated real-mode results.
  Demo remains separate and labeled. Unsupported remote job/agent/approval
  controls fail clearly rather than claim work happened.
  Dashboard counts/status are explicitly cached, zero alerts are not an all-clear,
  and recent activity uses saved task rows rather than fabricated completion claims.
- Typed notes, rounds, explicitly dated reminders and supported evidence feed
  the desktop's encrypted source-linked capture service. Proposed actions use
  the same canonical Action Center, exact revision checks and completion notes.
  Outlook/WhatsApp drafts remain internal review items. Pictures are evidence,
  not an implemented image-analysis result; unknown facts remain unknown.
- HTTPS verifies the paired certificate, endpoint and fingerprint. Pairing is
  per-device/revocable, with independent scopes. No plaintext HTTP, trust-all
  TLS, fixed default pairing secret or arbitrary desktop RPC is enabled.
- Encrypted pending operations retain stable request identity, original privacy
  requirements and the exact device pairing. Re-pairing cannot redirect old
  work. Partial sync failures remain visible; an unavailable AI model does not
  prevent non-AI refresh. Notes saved on the phone survive later refresh failure.
- Saved records and complete configuration use Android Keystore AES-GCM after
  real OS screen-lock/strong-biometric authentication. Additive Room upgrades
  and authenticated content migration preserve legacy data; corrupt records
  roll back rather than trigger destructive migration or new-key replacement.
  Known old demo caches are retained and labeled unverified.
- App backgrounding, manual lock and five-minute expiry close the protected UI
  and cancel foreground requests. Auth/workspace/cancellation epochs prevent
  later upload chunks or stale responses surviving a lock/re-unlock. Retries
  after unlock reuse saved identities and bytes. Automatic foreground polling
  can resume after Stop; no insecure background full-credential processing exists.
- External photo/file pickers require reauthentication on return. Unsent forms
  use encrypted RAM, not plaintext saved-state bundles. Process death can lose
  unsent forms/picker results, but not saved encrypted records.
- Optional background follow-up polling uses only the separate restricted
  notification key/certificate and generic notice IDs. It cannot read PHI,
  notes, chat or evidence. Display permission/channel success precedes receipt;
  receipt does not complete an action. Offline LAN reachability does not require
  Internet validation. Checks normally occur at least 15 minutes apart and may
  be delayed by Android battery policy; this is not instant push or emergency paging.
- On-device dictation is available only where Android API 31+ has an on-device
  recognizer. No cloud speech fallback exists. Large stored evidence has bounded
  readable previews without exporting base64 or private pairing metadata.
- The official Gradle wrapper/checksums, build regression/lint tasks, Room schema
  export and GitHub emulator workflow are restored. Test-only credentials belong
  exclusively to public synthetic fixtures or an explicitly disposable emulator.

## Verification and limits

Final Android results are recorded in `docs/ANDROID_VALIDATION_PROGRESS.md`:
45 unit tests passed with zero failures/errors/skips, including actual isolated
desktop HTTPS interoperability; lint passed with zero errors and 72 warnings.
The frozen-source app/test APK build passed, and the app signature was verified.
The production input digest remained unchanged throughout the build. Artifact
hashes and the development signing certificate are recorded in that report.
Hosted emulator acceptance also actually passed on source `a646614`, push run
`37399341176`: all four instrumented cases executed with zero skips, failures or
errors. This includes the real Android OS PIN prompt, authenticated Keystore
encrypt/decrypt, an unpaired dashboard/Notes workspace and relock/read rejection.
Hosted unit results are 44 passed plus one explicitly skipped external desktop
fixture case; the local fixture-enabled suite above passed all 45. Hosted lint
has zero errors and 74 warnings. The CI APK has a different development signer/
hash from the local exported APK; this establishes source behavior, not execution
of that local APK on a physical phone. See the exact run and validation report.
The real interoperability test requires `PRIVATE_AI_GATEWAY_TEST_PAIR_FILE`, an
ignored JSON fixture with `pairUri`; it explicitly skips without it. Never print
or commit that fixture or use a real account/PHI. The Windows local test used a
running isolated Node Core, encrypted SQLite and pinned HTTPS; calculator output
is not a new local language-model inference test.

Run with JDK 17 and the configured SDK using the checked-in wrapper:

```text
gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Inspect actual XML counts, lint reports and artifact hashes. GitHub workflow
`android-validation.yml` runs real OS-credential/Keystore tests on an account-free
disposable emulator; the four-case execution above is recorded separately from
compilation. Physical
phone, real LAN, existing signed-install upgrade, camera, local microphone,
notification delivery, live WhatsApp/mailbox and clinical acceptance remain separate.
No full AI generation is promised to continue while the phone is locked; foreground
requests cancel and can be reviewed/retried after unlock. The desktop must remain
awake and reachable; full access needs its app unlocked. Only explicitly allowed
generic reminder metadata can operate while the desktop app is locked.

Debug APKs are development candidates. They cannot upgrade an existing APK signed
with a different certificate. Preserve the original release key and phone data;
do not uninstall/wipe merely to make an upgrade pass. No production signing key
is added. SDKs, Gradle caches, keys, databases, APKs and pairing links stay outside Git.

## Future work instruction

Fetch both repositories first; verify origin/main, branch, worktree and versions.
Preserve unmerged review work and adapt it into the latest architecture. Use new
chatgpt/* branches and PRs; the owner merges. Keep the desktop as canonical local
AI/storage and use the existing v1 capability-scoped adapter, not a sample Python
bridge. Preserve OS authentication, encryption, migrations, stable retries,
provider/privacy/resource gates and source-linked clinical review. Do not replace
the current source with an older ZIP or make unavailable capabilities look successful.

The owner revoked the former 1% stop rule. A past one-time wakeup is not a recurring work schedule. Scheduling must use the app automation
tool and cannot be assumed successful if its request is rejected.

## 2026-10-14 Home-return acceptance correction

Both exact-head 13694876 hosted runs passed two actual OS-PIN/Keystore cycles,
but the test reopened the Activity before Home delivered onStop. Continuous logs
show PAUSED then RESUMED without STOPPED; no new OS authentication was requested.
The test now waits, bounded to ten seconds, for actual ActivityScenario CREATED
after real Home, asserts the workspace locked, and then launches normally. No
production authentication behavior or assertions were weakened. Focused state
tests reran: 10 passed, zero failures/errors/skips; lint zero errors/42 warnings;
instrumented test sources compiled and packaged. The production APK and existing
signer are unchanged. Fresh exact-head hosted device acceptance is still pending;
physical phone acceptance remains separate. Evidence is retained outside Git.

The current assignment permits Codex to merge this review only after fresh
exact-head GitHub checks pass; see the dated AGENTS authorization.
