# Secure phone sessions and encrypted local auto-drafts — October 19, 2026

Current development branch `chatgpt/secure-unlock-autodrafts-2026-10-19` starts
from accepted screenshot main `5dc5501b770cb09d1e86a9fe4ef9d5fd86639b45`.
Current candidate version: **1.2.4-dev / code 7** (original app ID and signer).
The owner authorized 5 (default), 10, 20 and 30 minute foreground sessions and
requires an actual secure Android phone screen lock. None/Swipe, unavailable
security state and a currently locked phone fail closed. Changes require fresh
Android authentication; phone lock, backgrounding, Lock now and security expiry
still revoke access. There is no indefinite Off option, application PIN bypass,
typing/polling renewal or exported key. Existing data/keys/pairings are preserved
through recoverable authenticated policy migration before workspace readiness.

The owner's later auto-draft instruction replaces the earlier discard proposal:
**changing a timeout never asks to discard or clears unfinished work**. Settings
has one default-on **Auto-save drafts** control. Draft text and evidence stay
encrypted on this phone and never auto-send or sync to the desktop. Turning it
off retains existing saved drafts; explicit Delete draft requires confirmation
and protects newer revisions against stale autosave callbacks. Failed saves
remain visible and RAM fallback is not described as durable storage.

Chats, Notes, manual Library documents, new chat/meeting titles, action edits and
internal recipient/subject/body edits reuse one local draft controller. Pairing
links and search queries are excluded; meeting audio already uses its existing
durable chunk outbox. Restore/loading gates precede editable composition. Small
text fields and stable evidence references avoid rebuilding multi-megabyte
attachment JSON on each keystroke. The core coalesces encrypted writes and uses
bounded evidence/body hydration. Successful durable Send/Save acknowledgments
clear only unchanged submitted fields, including with auto-save off; encrypted
transaction receipts reconcile commits that cross lock/restart. Other edits and
failed submissions remain. Screenshot preference/clinical policy stay separate.

**Current-source validation is pending.** Fourteen new UI behavioral unit cases
are authored (prompt attempt ownership, working choices/phone-lock capability,
preferences/conditional acknowledgments, actual Compose+Room restore/delete
races), retaining existing unit identities. All ten prior device cases remain
unchanged; two new actual cases bring the required inventory to twelve: a fresh
pre-PIN unprotected-phone rejection, then a real OS-authenticated session beyond
300 seconds, five-MiB unsent and saved/pending evidence hydration, lock revocation
and normal authenticated migration back to five minutes. These are authored
assertions, not passes. No local emulator, phone installation or CI execution
was performed by this UI implementation step. Coherent build/unit/lint and all
twelve hosted actual-device cases remain required; core test totals/results will
be recorded from real output after the joint source freeze.

After the schema 4-to-5 and opt-in PAI3 storage upgrade, an older APK is not
an in-place rollback. Returning to five minutes restores the original cipher
format, not Room schema 4. Update over the same application ID with the original
signer; never uninstall or clear app data to recover. Preserve keys and pairing,
and use a compatible corrected update if recovery is required. Old APKs remain
reference evidence, and the owner's installed app is unchanged.

The owner deferred hands-on phone/OEM testing, not our verification. Background
follow-up notices remain Android-scheduled best effort (15-minute minimum,
Doze/battery/network/PC reachability can delay them); they are not guaranteed
immediate alerts. Owner-phone/OEM, live accounts, microphone, notifications,
LAN, reboot and installed-app upgrade acceptance remain unperformed/deferred.
No cloud/relay/dependency/signing identity or owner installation is added.

---
Prior accepted and failed-run evidence follows unchanged.

## Screenshot device-fixture follow-up — October 19, 2026

Run39305305505 atc44fe16:116 hosted unit passes, two named unavailable desktop-fixture
skips, no unit failures; lint0errors/77warnings. Actual Android:9passed/1failed/0skipped.
Only the new screenshot case failed: it pressed Home, observed lock/protection, then
called a helper that pressed Home again. The second command returned false at line433.
All preceding screenshot on/off, persistence, recreation, lock and background-protection
assertions passed; this is not a confirmed application defect. Raw first evidence is retained.
The new case now asserts the first Home, observes actual stopped state, relaunches via
the normal package intent, and requires the actual Android credential prompt, continued
locked access and secure window. Production code and all nine prior cases/helper remain
unchanged. Fresh exact-head full checks/all ten device cases remain required; no merge
or owner installation is accepted from the failed run.

## Isolated screenshot candidate — October 19, 2026

This candidate contains the on/off **Allow screenshots** setting, default off,
the standard Settings gear and centered existing duration rows, on top of the
four earlier mobile usability fixes. It uses native Android window protection
and the existing Material components; no new dependency or cloud service.
The choice is saved locally and applies immediately to the unlocked foreground
workspace. Turning it off restores screenshot/screen-sharing protection. Startup,
opening/locked screens and background transitions remain protected; API33+ also
disables recents snapshots. The choice is independent of clinical processing mode.

Unfinished longer-session crypto/data changes are preserved in a different
worktree and are excluded. The existing five-minute OS-key policy, saved data,
pairing, signing configuration, protocol, dependencies and workflows are unchanged.
No owner installation is replaced.

The screenshot baseline previously passed six focused local tests and test-APK
assembly. A bounded independent review found one new device-fixture defect:
after Activity recreation the retained one-shot prompt gate needs the normal
Unlock button. The new case now requests that retry and still performs actual
OS-PIN authentication; its assertions and all nine original cases are retained.
This is not an authentication bypass or a change to production prompt behavior.

Fresh local CI-scope tests passed16/16. A fresh local full Gradle run is deferred
because Windows reports about1GB free RAM; existing checkpoint passes are not
relabelled as current integrated execution. The exact-head hosted build, unit,
lint and all ten actual API34 device cases are required before merge. The two
external desktop-fixture cases may be explicitly skipped in hosted unit tests,
as before; skips are not passes. Physical owner-phone/OEM capture, screen-sharing,
recents and installation acceptance remain unperformed.

---
Previous checkpoint follows as historical evidence.

# Screenshot preference and settings polish — October 19, 2026

Based on clean review head `44ceaa59cc66c0ca7b3719d78fc9c521fed546c4` and freshly
fetched main `55c09a414f30cd7641f103b2c3a86d8836a1245d`, still **1.2.3-dev / code 6**.
The owner requested an **Allow screenshots** setting. It defaults off and can change
only inside the OS-unlocked, ready foreground workspace. It uses existing Android
SharedPreferences for one nonsecret boolean, separately from clinical chat restrictions.
Opt-in permits capture/screen sharing of unlocked content. Lock/unlock/opening surfaces
remain secure. Creation, pause and lock synchronously set `FLAG_SECURE`; clearing uses
live foreground/authentication/vault checks during the UI commit. API 33+ also disables
recents snapshots explicitly. Older devices retain the synchronous secure-window
background fallback; physical OEM screenshot/recents behavior remains unverified.

Duration radio/text rows are vertically centered. The existing Dashboard Settings
entry now uses the existing Material filled cog with the same accessible label/action.
No session duration, five-minute lease, key, authentication, database, pairing, network,
dependency, workflow or version change is included. Longer sessions are a separate
design decision; the disabled unsupported choices have not been enabled here.

Six new preference/actual-Compose unit cases and a tenth real OS-unlock device case
are added; all nine existing device cases/assertions are verified unchanged. **Focused
local tests passed 6/6, zero failures/errors/skips; offline test-APK assembly passed.**
The first run was 5/6: only an artificial whole-Compose-root recreation fixture failed;
its raw XML/report is retained. Keyed Settings-subtree recreation retains the persistence
assertions and passes. This is screenshot-only checkpoint evidence; longer-session core
and UI integration is separately in progress and will require coherent fresh checks.
No local emulator, owner installation
or distribution is performed. Fresh exact-source hosted lint/unit and **all ten real
API 34 device cases** remain required. Prior failed/canceled runs do not accept this
source, and prior-source results below are historical rather than current acceptance.

See [scope and raw validation](docs/FIVE_USABILITY_FIXES_2026-10-18.md).

---
Previous `44ceaa5` checkpoint follows unchanged.

# Five usability fixes — Android F1–F4, October 18–19, 2026

Source base: freshly fetched authoritative main `55c09a414f30cd7641f103b2c3a86d8836a1245d`;
branch `chatgpt/five-usability-fixes-2026-10-19`, **1.2.3-dev / versionCode 6**.
App ID, original signer, OS authentication, encryption, existing records, pairings,
privacy requirements, wire protocol, database schema and workflows are unchanged.
No new package, cloud API, relay, owner installation or distribution is introduced.

- Active chat mounts the protected snackbar and displays each pending/review request's
  stored delivery error. Review-required delivery is explicitly unconfirmed.
- Evidence saves to an encrypted Notes/Library capture with a durable identity before
  upload. Only unchanged submitted fields clear; failed drafts and later edits remain.
  The encrypted RAM form acknowledges that save before a possible UI disposal. Duplicate
  taps are blocked. Feedback identifies actual pending/acknowledged/review state. Files
  remain evidence for human review, without chat file upload or AI image interpretation.
- Chat reuses the primary screen's check timestamp, syncing/stale badge and check callback.
- **Ask again · new request** repeats the exact completed original phone text question
  with a new ID, same pairing and at least its original privacy restrictions. It does
  not rewind context. Pending/review/unknown-policy imported history is excluded.

**Baseline local validation (source `217718d`):** both APK builds pass; full unit suite **112/112**, zero
failures/errors/skips, including both actual current-Core legacy and v2 bootstrap TLS
cases. New Compose 6/6, encrypted-draft 3/3 and repository 28/28 pass. Both APKs verify
original signer `4f2f15654f1730b6b27a942464629d05b8388c5f0c7fa60863c3ffff6c435fbd`;
package remains `com.aistudio.privateai.kq7zwa`, version 1.2.3-dev/code 6.

**Hosted PR #10 run `39244077514` at source `217718d` failed:** unit XML records
112 tests: 110 passed, 0 failures/errors and 2 external-Core-fixture skips. Hosted lint
completed with 0 errors and 76 warnings. Actual API 34 device XML records 9 tests:
8 passed, 1 failed, 0 errors/skips. The new chat evidence case failed at line 272 on
full `AnnotatedString` equality although its dump printed empty `EditableText`;
the earlier feedback and evidence-removal assertions passed. Preserve this raw failure.

The follow-up changes only that instrumented assertion and these validation notes.
It waits at most 10 seconds for exact `EditableText.text == ""`, then explicitly asserts
the same value; an absent property or whitespace fails. Compose 1.7.2 equality also
compares styles/annotations while its printed string contains only text. The runtime
metadata difference was not captured, and a scheduling contribution is not excluded.
All receipt, feedback, evidence, disabled-send, encrypted-record, failed-draft and real
OS-lock assertions remain. Local corrected test-APK assembly **passed offline** (one worker, no device execution). Fresh full
**nine-case actual-device validation is pending** before merge/distribution; compile
success cannot accept device behavior. Production code and unreleased 1.2.3-dev/code 6
are unchanged. Local lint had not executed due two approval-review startup timeouts;
the hosted lint result above is an actual execution for `217718d`. Physical owner-phone
and live-account acceptance remain unperformed. Prior 1.2.2 results do not accept this candidate.

See [scope, retained first failures and validation](docs/FIVE_USABILITY_FIXES_2026-10-18.md).

---
Historical handoff follows unchanged.

# Accepted private preview checkpoint - October 18, 2026

This checkpoint supersedes historical pending/budget/failure notes below for this
release. Preserve historical evidence; do not rerun completed tests without reason.

- APK/source: 163398051f72b943fb74242462e5ee5a83ac8be1,
  1.2.2-dev/versionCode 5. Accepted merge main:
  2ca8ffc887a3ade5928b793a5ccca6d1277a5c74.
- Both exact-source review/push workflows passed (39082870008/39082867773),
  as did merged-main 39106474740. Both original source artifact archives record
  8/8 actual API 34 emulator cases with zero skips, including OS authentication,
  actual IME visibility, composer/history geometry, bounded multiline draft and
  encrypted-history retention. Hosted units: 96 pass, two named unavailable
  external desktop-fixture skips. Lint: 0 errors, 76 warnings. Skips are not passes.
- Distributed APK SHA256:
  da5b96f5b9c12823301fb06535b1f743aca59acf8becac531ee023aa05f2fae9
  All 588 non-signature payload entries match the accepted CI APK. Public retained
  signer SHA256: 4f2f15654f1730b6b27a942464629d05b8388c5f0c7fa60863c3ffff6c435fbd.
- Immutable private preview, exact source and VALIDATION.json/checksums:
  https://github.com/yosefbf-bot/AI-Mobile-companion-app/releases/tag/private-preview-1.2.2-2026.10.18
- Physical owner-phone update/OEM keyboard/Keystore, live LAN/camera/microphone/
  notifications and live approved account workflows are NOT established by hosted
  tests. Owner confirmation of the earlier unlock fix is not keyboard acceptance.
  Do not uninstall/clear records or rotate signing identity to bypass an update
  failure. No owner installation, records, pairing or key was replaced here.

This is a documentation-only acceptance correction. No app/protocol/database,
permissions, authentication or encryption behavior changes. Its own GitHub checks
remain required. Original source archives retain the exact tested source commit;
these notes do not relabel binaries or rewrite historical validation receipts.

---
Historical handoff follows.

## October 17 chat keyboard visibility correction (pending validation)

Owner confirms the retained-chat unlock fix now works on the phone. Based on
verified main6f450f589ac04509c8ef887f97f907d9e210e99d, the focused keyboard
branch applies Compose IME insets to the chat Scaffold and navigation-bar insets
to the composer. The existing scrollable history and four-line input limit remain;
the input and send button should move above the keyboard without a full-screen
editor. Existing adjustResize is retained. No extra package, network, permission,
protocol, authentication, encryption, pairing or data migration is introduced.
Version1.2.2-dev/code5 distinguishes the installable update from1.2.1.
An eighth actual-device case requires real OS unlock and a visible real keyboard,
checks input/send/history geometry, long-draft bounds, unchanged encrypted history
and retained draft after dismissing the keyboard. Compile, unit/lint and fresh
exact-head device results are PENDING, not implied by the added test. Keep the
existing owner installation and signer; do not uninstall or clear application data.

## October 15 retained-chat authentication correction

Read [the 1.2.1-dev correction](docs/COMPANION_UNLOCK_PREVIEW_FIX_2026-10-15.md).
Verified authoritative main `ffa762a1495051449bd995d165dd8f7f7f50ac6f` is the base
for `chatgpt/companion-auth-loop-2026-10-15`. The Chat list previously decrypted
the legitimate empty optional preview of new/shared chats, causing the strict
vault decoder to revoke access immediately after successful face/PIN unlock.
The UI skips only exact empty absence; all nonempty authenticated/malformed
inputs retain the original decoder and fail-closed lock. Version1.2.1-dev/code4;
appID, original key, cipher, OS-authentication/session policy and data unchanged.
Three actual Compose/strict-codec unit regressions and a seventh real OS-PIN/
Keystore retained-shared-cache case are added. Independent source review and
diff-check passed. After freeRAM recovered, the new unit fixture failure was
reproduced/preserved and corrected using three visibly measured static rows,
with all strict rejection assertions retained. Focused3/3 passed locally, zero
failures/errors/skips. Full current-head build/unit/lint/sevenactualdevice gates
remain required; twoCoreHTTPS cases have not been rerun. Initial hosted SDK
download and unmeasured-unit failures are historical first evidence, not passes.
Do not transfer prior95/sixcase results to this head or distribute an unaccepted
APK. Preserve the phone installation/pairing and original signer for an update.

## October 15 current repository-lock regression checkpoint

Read [current acceptance](docs/COMPANION_ACCEPTANCE_2026-10-14.md).
Production repository locking now revokes readiness/generation before cancelling
I/O, and unlock/config ready publication shares the commit guard. Two
deterministic cancellation/queued-unlock regressions were added. Full local
suite **95/95**, zero failures/errors/skips, including **both actual Core/SQLite/
pinned-HTTPS legacy and v2 interoperability cases**; focused bootstrap19/19.
App/testAPK builds and final lint passed (0 errors,43 warnings). Camera fixture
scheduler correction retains bounded real scrolling and all security assertions.

New appAPK SHA256 `a8312afcbb8f9615610bd768c53b82f616e55155737188f5fe7667e4082b7563`;
new testAPK `e7a357ce0ce59582f363a018a39da10a567fcb619f579b505754fb65a42e7092`.
Existing signer `4f2f15654f1730b6b27a942464629d05b8388c5f0c7fa60863c3ffff6c435fbd`
verified for both. Version1.2-dev/code3, appID, records, original300-second key and
pairings preserved; owner apps/data not installed over. Source is based on
verified main7e817069; root owns fresh review commit/publication.

**All six fresh exact-head hosted device cases remain pending.** Keep PR4 draft
until all current-head checks pass. Prior347 push/unit and camera-device failures
and first full95 helper failure are preserved; do not represent old device runs
or local compilation as current device acceptance. Synthetic fixture closed;
controlled Gradle daemons stopped. Earlier sections below are historical,
including statements that the former c59247 production APK was unchanged.

---

## October 14 finite-scroll device-test correction

Both e67b323 device runs passed real app expiry and both raw original-key
rejection assertions (software OS/framework proof, not physical TEE acceptance).
Only3 of6 cases attempted:2 passed, OS-credential UI case exhausted its192MB
heap in Compose1.7.2's looping performScrollTo after known-index scroll succeeded.
The new two-file test correction removes that redundant loop and caps remaining
camera/settings scroll actions, retaining every visible/auth/permission assertion.
No production heap, crypto, key, database or session-policy change. Fresh14 focused
units, testAPK build and lint passed;0errors43warnings. Production APK remains
c59247c71d8faf990af853b0ba6416696b7e1a9ffe5194db68518595c6f93bbb;
latesttestAPK4e71bda1c6270174d57f96337eaae6692e1544de2c784e72db02c652ae1ac0e7,
signer unchanged. All6 current-head hosted cases must pass before PR4 merges.
Original bf4/e67 archives, XML and first stacks remain preserved; never repeat
unproved hardware or completed-release claims. Owner app/data/pairings untouched.

## October 14 corrected device acceptance tests

Read [the current acceptance checkpoint](docs/COMPANION_ACCEPTANCE_2026-10-14.md).
Full local source suite93/93 and14 focused correction tests passed; builds/lint
passed with0 errors/43warnings. Production APK/signing/key policy are unchanged.
Both firstbf4 hosted runs failed and are retained. Three test-only corrections
retain both raw OS expiry assertions and all UI/permission/lifecycle checks;
fresh six-case exact-head device checks are required before PR4 merges.
No owner installation/data/key/pairing change. Earlier pending results below
are historical; current exact-head CI remains authoritative.

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
