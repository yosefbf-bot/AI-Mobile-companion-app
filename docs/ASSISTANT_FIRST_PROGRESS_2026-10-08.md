# Companion simplification checkpoint — October 11, 2026

Base origin/main 1e15c200d646cae647cb16139d41d66f2b9e16e0 was fetched and unchanged
October 11. Branch chatgpt/assistant-first-2026-10-07, version 1.1-dev/code 2,
application ID unchanged. Matching desktop branch is
chatgpt/assistant-first-workflows-2026-10-07 at the Private-AI-OG-google-version repository.
The owner merges both mains. The old 1% stop instruction was explicitly revoked.

Chat opens by default, with one Dashboard and simple Notes, Meetings and Library.
Notes uses one text box with optional manual fields. Main screens show a connection
light and encrypted last-successful-sync receipt; stale checks turn amber after 90 seconds.
Last sync survives unlock/reopening only for the same pairing; partial sync is not success.
Settings offers saved System/Light/Dark appearance and retains the previous dark default.
Theme is a non-sensitive device preference and applies before vault unlock.

Meetings records native 16-kHz mono PCM in bounded 20-second encrypted chunks, scoped to
the original pairing. The PC transcribes and drafts minutes locally. IDs and SHA-256
receipts make retries idempotent; finalization requires the exact saved audio count.
The app must stay visible/unlocked on the recording screen. Lock/background/navigation
or OS-authentication expiry stops recording; an uncommitted partial chunk up to 20 seconds
can be lost. Saved chunks persist and upload after reconnect to the same PC. 60-minute
recording and 512-MiB queue bounds apply. No online speech service is used.

HTTP clients share a bounded pool per exact pairing; the real extended interoperability
test exposed the prior per-request pools exhausting the desktop's connection limit.
Certificate validation still runs per request. Re-pairing clears the previous pool.
Existing OS auth/Keystore, Room additive migrations, encrypted pending work, restricted
notification-only background credentials and Demo separation are preserved.

Validation: testDebugUnitTest, lintDebug, assembleDebug and assembleDebugAndroidTest
passed. 50 tests, zero failures/errors/skips, including actual synthetic Node Core/SQLite
pinned-HTTPS chat, audio, notes/actions/drafts/evidence and reminder interoperability.
Lint has zero errors and 73 warnings. Evidence is in the desktop ignored
Validation/assistant-first-2026-10-07/android-dark-final.log plus Gradle XML/reports.
The appearance regression verifies persistence across preferences instances and system
selection without granting vault access. Physical-device/emulator execution of the new
recording/theme UI has not occurred. APKs use development signing; preserve existing
release signing and phone data. No production signing key is included.

Limitations: automatic multi-speaker identity/diarization is not implemented; attribution
needs review on the PC. Local speech assets require initial desktop setup. Full access
requires the desktop awake/unlocked and the phone authenticated. Generic background
notifications are separately permissioned and may be delayed by Android. Outgoing
Outlook/WhatsApp messages require approval on the PC; the phone cannot approve remotely.
No live accounts/PHI were used. Source checks do not establish physical microphone,
Keystore, LAN, notifications, or existing-signed-install upgrade acceptance.

Read SHARED_DESKTOP_WORKFLOWS.md and the desktop ANDROID_COMPANION_CONTRACT.md for
setup and wire details. Fetch both mains before any new work or publication. Keep review
branches and let the owner merge. New source does not update installed apps automatically.

Debug artifact app-debug.apk: SHA-256 4b3ea024d6d748a382682587834f1d5bf7168c7f83812b2c786828c79fb91425

Debug artifact app-debug-androidTest.apk: SHA-256 82162b89ad93f842c577ce4e8b4d3a7783a852d8301b6e3e2fac51403cdb4461
