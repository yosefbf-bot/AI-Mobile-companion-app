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
actual error in active chat, and checks that lock removes chat/feedback. **It has not yet
executed.** Compilation and Robolectric do not establish hosted or physical-phone acceptance.

## Current local results

- `assembleDebug` and `assembleDebugAndroidTest`: passed for the current worktree.
- `testDebugUnitTest`: **112 executed, 112 passed, 0 failures, 0 errors, 0 skips**. Both
  actual current-Core `DesktopGatewayInteropTest` and `BootstrapDesktopInteropTest` passed
  using a refreshed account-free synthetic fixture. No previous-source result is reused.
- `lintDebug`: **NOT EXECUTED locally**. The initial approval request and one permitted
  retry both timed out in automatic permission review before process creation. No lint
  result, zero-error claim or warning count is inferred. A separate earlier XML archive
  approval failed on review-model capacity; the full raw unit XML remains retained.
- The coordinator will publish a reversible review PR. Existing mandatory hosted lint,
  unit and **all nine actual API 34 emulator cases** remain required before merge and
  distribution, with exact-source raw XML/check inspection. No gate or workflow is weakened.
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
