# Android F1–F4 usability candidate — October 18–19, 2026

Base: freshly fetched main `55c09a414f30cd7641f103b2c3a86d8836a1245d`.
Branch: `chatgpt/five-usability-fixes-2026-10-19`; version `1.2.3-dev` / code `6`.
This document describes Android; desktop F5 is coordinated separately.

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
