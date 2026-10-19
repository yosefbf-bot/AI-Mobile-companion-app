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

# Android F1–F4 usability candidate — October 18–19, 2026

Base: freshly fetched main `55c09a414f30cd7641f103b2c3a86d8836a1245d`.
Branch: `chatgpt/five-usability-fixes-2026-10-19`; version `1.2.3-dev` / code `6`.
This document describes Android; desktop F5 is coordinated separately.

## Owner-requested screenshot preference and settings polish

Follow-up to clean `44ceaa59cc66c0ca7b3719d78fc9c521fed546c4`; main freshly verified
at `55c09a414f30cd7641f103b2c3a86d8836a1245d`. The version stays 1.2.3-dev/code 6.
**Allow screenshots** is a default-off, nonsecret local display preference in Privacy &
Unlock, independently of clinical processing policy. Only an actually authenticated,
ready foreground workspace can change it or clear `FLAG_SECURE`. Every attempted clear
rechecks live activity foreground, Android-authenticated access and vault readiness.
The preference callback and authorization check share the existing encryption monitor
for a bounded nonsecret settings write, without crypto/network work. Composition uses
observable foreground state and synchronous `DisposableEffect`, whose disposal secures
the window. Creation, pause and explicit lock protect synchronously. API 33+ also sets
[`setRecentsScreenshotEnabled(false)`](https://developer.android.com/reference/android/app/Activity#setRecentsScreenshotEnabled(boolean));
older versions retain the pause/lock secure-window
fallback. No background capture or permission is added. Opt-in can expose screenshots
and screen sharing of unlocked protected information. Physical OEM/recents enforcement
still needs separate acceptance.

The duration radio/text rows are centered; their selection/timeout behavior is unchanged.
The existing Dashboard Settings entry uses Material's filled Settings cog, preserving its
action, test tag and accessible label. Existing AndroidX Compose/Material components
(Apache 2.0, [official AndroidX source](https://github.com/androidx/androidx) inspected) and the app's
SharedPreferences mechanism are reused; no new package/assets/cloud service is needed.
No session/key/lease, protocol, record, pairing, dependency, workflow or version changes
are included. Longer unlock sessions remain a separately reviewed design decision.

New regressions: `ScreenCapturePreferencesTest` has three cases for default blocking,
persisted independent choice, and malformed-value fail-closed behavior;
`PrivacySettingsCompositionTest` has three actual Compose cases for switch/recreation,
independent clinical controls, and measured radio/label centering without enabling
unsupported durations. One tenth instrumented case requires real OS unlock, verifies
default blocking, rejection of a locked stale callback, both switch directions, retained
choice across Activity recreation, protected lock/startup/background, and capture only
after actual unlock. The prior nine cases/assertions are retained. It uses account-free
data and resets only the disposable fixture preference. Window-flag checks on a hosted
emulator do not establish physical OEM capture acceptance.

**Screenshot-only local checkpoint:** focused new tests **6/6 passed**, zero failures,
errors or skips; `assembleDebugAndroidTest` **passed offline**, 55 seconds, 55 tasks
(5 executed / 50 up-to-date). The first focused run was **5 passed / 1 failed**: after
successfully persisting the switch, the test removed its whole Compose root and could
not find the re-added Settings node. The fixture now recreates the keyed Settings
subtree, discarding its remembered state while retaining the host and the same persisted
on/off assertions. Its original XML/report and corrected 6/6 XML remain retained.
The actual-device test still requires real Activity recreation; it has not run locally.
The test APK SHA256 is `695f6b19bf1e6b706c10a5b1ce47c0455dd4c0efb194ca6c2f98686a54de9388`,
verified generated-debug signer `c22024dda78abdc376b87e518360e2f50c39021ceca7ab1a05d6100cee288c80`.
This fixture is not an owner upgrade payload. The retained original-signer production
APK remains earlier-source evidence, with no installation or signing change.
Longer-session core/UI integration is separately in progress, so these six passes and
this compile do not accept that combined source. Fresh hosted lint/unit plus all ten
current actual API 34 cases (and any additional session cases) remain required before merge/distribution.
No local emulator/owner installation is performed. Evidence will be retained in ignored
`app/build/android5-screenshot-2026-10-19/`; older failure/compile receipts stay intact.
The previous nine-case failed/canceled run does not accept this screenshot source.

## Behavior

1. Active chat mounts the existing protected snackbar and displays the stored error
   beside the affected pending/review request. Lock cancels/dismisses feedback. Review
   says delivery is unconfirmed because an interrupted request may have reached the PC.
2. Attachments save encrypted Notes/Library captures. A local receipt carries only the
   session ID, durable record ID, kind and state, and is published after the local commit
   before network work. Raw submitted text/evidence are compared independently before
   clearing. Later edits, replaced evidence and failed drafts remain. The encrypted RAM
   draft acknowledges the same commit synchronously so a lock/navigation before the next
   UI frame cannot restore the unchanged submitted form. The send guard is set before
   launching work; Stop keeps it until cancellation completes. Feedback uses actual
   pending/acknowledged/review state. Files are review evidence, without chat upload or
   image interpretation.
3. Chat receives the same last-check timestamp, syncing override and tap-to-check
   callback as the primary screen, reusing its existing 90-second stale behavior.
4. **Ask again · new request** replaces the former assistant-text echo labeled Regenerate.
   It selects the exact locally linked original completed phone text question. The
   repository reloads and verifies session, completed status, text-only form, same
   pairing and minimum original local/HIPAA requirements. It uses a new durable request
   ID and the entire original question; it does not rewind conversation context. Imported
   history without original policy provenance and unknown-outcome pending/review requests
   have no action. Existing queue retries retain their original IDs. Over-limit originals
   fail explicitly without truncating or queuing them.

No app ID, signer configuration, key, pairing, OS authentication, capture/privacy policy,
endpoint, protocol, database, dependency or workflow change is included. No owner app,
account, pairing, evidence or installation was used, replaced or distributed.

## Behavioral tests

Fourteen new unit cases use existing architecture:

- `ChatSubmissionCompositionTest` 6/6: displayed chat feedback/error; stale/syncing badge
  and check callback; duplicate taps and clearing only submitted fields; later text/file
  preservation; failed local-save retry; text local-save clearing independent of later
  delivery; and exact original-question repeat eligibility despite another later question.
- `ChatDraftAcknowledgmentTest` 3/3: encrypted RAM clearing before recreation; independently
  newer text/evidence retention; and preserving a failed draft without a valid snapshot.
- `GatewayRepositoryTest` 28/28, including five new encrypted Room/synthetic loopback TLS
  cases: stable capture identity/deferred replay without chat upload; receipt before upload
  and acknowledged save despite metadata failure; validation failure without a receipt;
  full original question/new identity/minimum privacy despite a large assistant answer;
  and rejection of uncertain delivery, unknown/different pairing or over-limit originals.

A ninth actual-device case is compiled in `OsCredentialKeystoreAcceptanceTest`. It requires
real OS PIN/Keystore unlock, saves synthetic evidence offline, asserts visible snackbar,
empty editable input and encrypted durable payload, preserves an over-limit draft with its
actual error in active chat, and checks that lock removes chat/feedback. Its first hosted
execution failed as recorded below. Compilation and Robolectric do not establish hosted
or physical-phone acceptance.

## Hosted failure and test-only correction

PR #10 source `217718d1b102f5efbe20ec0740cad809ebd818b2`, run `39244077514`, failed.
Raw unit XML: **112 tests, 110 passed, 0 failures, 0 errors, 2 skips** (the unavailable
external-Core-fixture cases `desktopBootstrapCommitsVerifiedConfigurationAndRejectsRealReplay`
and `realDesktopRoundTripReplayHistoryEvidenceActionsDraftsAndNotices`). Hosted `lintDebug`
executed: **0 errors, 76 warnings**. Actual API 34 device XML: **9 tests, 8 passed,
1 failure, 0 errors/skips**. All original eight device cases passed, including real
five-minute Keystore expiry and keyboard geometry. These are not nine-case acceptance.

The sole failure was the new chat-evidence case at source line 272, after visible save
feedback and attachment removal passed. Its failed `expectValue(EditableText,
AnnotatedString(""))` assertion printed `EditableText = ''` and selection `TextRange(0, 0)`.
Resolved Compose 1.7.2 bytecode shows that `expectValue` calls structural equality;
`AnnotatedString.equals` includes text, span styles, paragraph styles and annotations,
whereas `toString` prints only text. Hidden metadata can therefore differ despite an
empty dump. The failed run did not record those metadata fields, so their exact difference
and any scheduling contribution remain uncertain; no production defect is claimed fixed.

The correction imports the existing `getOrNull` API, waits at most 10 seconds for the
editable semantics' exact `.text == ""`, then explicitly asserts that same textual value.
Missing editable semantics, whitespace and an uncleared draft fail. The durable receipt
wait, exact feedback, attachment absence, disabled send, encrypted capture/payload,
failed-draft retention, no-chat-upload and real OS-lock checks are retained. Newer-edit
and duplicate-submit regressions remain unchanged. Only this device-test file and the
two current validation documents change; production source, dependency, workflow,
authentication and unreleased 1.2.3-dev/code 6 remain unchanged. No skip or unlock bypass
is added. Local corrected test-APK assembly **passed offline** (one worker, no device execution). A fresh complete nine-case
device run and exact-source checks remain **pending and required** before merge/distribution.

The corrected local test APK SHA256 is `41141f071be117144e33654f523cdeb7fdce336ffe963f750ee7e5f10af1fbdb`;
its generated debug signer verifies as `c22024dda78abdc376b87e518360e2f50c39021ceca7ab1a05d6100cee288c80`.
It is a compile-only test fixture, with no local installation/device execution, and is
not an owner upgrade payload. The production APK retains its baseline hash and original
signer. The first receipt helper incorrectly required their signer identities to match;
that receipt-scope assertion failure is preserved separately and was not a build failure
or a signing-source change. No rebuild or re-sign was performed. The full assembly log
and certificate output are retained with the ignored diagnosis receipt.

The failed raw archive is retained at `Validation/owner-funded-check-resume/
five-AI-Mobile-companion-app-pull_request-217718d/android-validation.zip` in the desktop
evidence checkout, with `verified.json` and complete job logs alongside it. Archive
SHA256: `04316c8f1ebdacca1334a64db3f1758bf23275c2f190174f9b612ea07fe4208e`.
The local correction diagnosis/compile receipt is kept in the ignored
`app/build/android5-editabletext-2026-10-19/` folder. Prior proofs remain unchanged.

## Baseline local results (source `217718d`)

- `assembleDebug` and `assembleDebugAndroidTest`: passed before this test-only correction.
- `testDebugUnitTest`: **112 executed, 112 passed, 0 failures, 0 errors, 0 skips**. Both
  actual current-Core `DesktopGatewayInteropTest` and `BootstrapDesktopInteropTest` passed
  using a refreshed account-free synthetic fixture. No previous-source result is reused.
- `lintDebug`: **NOT EXECUTED locally**. The initial approval request and one permitted
  retry both timed out in automatic permission review before process creation. No lint
  result, zero-error claim or warning count is inferred. A separate earlier XML archive
  approval failed on review-model capacity; the full raw unit XML remains retained.
- PR #10's first hosted lint/unit execution is recorded above, including its two unit
  skips and failed device case. Fresh exact-source checks and **all nine actual API 34
  emulator cases** remain required before merge/distribution. No gate/workflow is weakened.
- Package `com.aistudio.privateai.kq7zwa`, version `1.2.3-dev` / code `6` verified.
- App SHA256: `c22319eb0ceeff0eed54a3815ff15c2df48042a02580a2e44f10fba1cd163c98`.
- Test APK SHA256: `90a0318ff204f43834d953f6bc92043db4132ce843edf08b9aa647b4a5bfccf5`.
- Both APKs verify original signer SHA256
  `4f2f15654f1730b6b27a942464629d05b8388c5f0c7fa60863c3ffff6c435fbd`.
- Neither APK is installed or distributed. Physical owner phone/OEM keyboard, LAN,
  camera/microphone, notifications and live account workflows are unperformed here.

## Retained first evidence

First logs/XML are preserved in the coordinator workspace with the `android5_` prefix:
the JDK default Unix-domain temporary-path startup failures, the test entity-import compile
failure, the first completed 34-case run (30 passed / 4 failed), the corrected nine-case
run (8 passed / 1 assertion failure), and the initial instrumentation import failure.
The JDK startup issue was resolved with the prior validated build-only temp-directory
setting. Compose now samples bounded frames and disposes before framework cleanup. The
remaining empty-input assertion was corrected to test EditableText directly: its failed
XML already showed an empty editable value plus the placeholder. Assertions were retained;
the final full 112-case run passed. Instrumentation import/entrance-frame corrections compile.

These local results do not accept the candidate for distribution. Preserve the existing
owner installation, original signer, pairings and encrypted records until current hosted
acceptance and the separately authorized distribution/update steps are complete.
