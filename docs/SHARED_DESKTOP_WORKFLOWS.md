# Reconnecting after an uncertain chat delivery — October 19, 2026

If a chat was already attempted but its answer was not acknowledged, automatic
retry stops after 23 hours or if its attempt timestamp is invalid/future. The
message remains saved with **Review required**. Check the conversation on the
desktop before manually sending a new request; it may already have an answer.
The phone keeps the original request ID and never silently creates a replacement.
Messages composed offline that have never been attempted can still send normally.
This bounds automatic retry using the phone clock. It is not an exactly-once
guarantee across manual clock changes; review desktop history when uncertain.

This behavior is in the 1.2.5-dev candidate and awaits its own full validation.
The 1.2.4-dev secure-session/draft update passed both PR and main automated gates.
Older validation notes below are historical; owner-device acceptance stays separate.

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

## Android 1.2.3-dev usability candidate — October 18–19, 2026

The latest follow-up adds Settings > Privacy & Unlock > **Allow screenshots**, off by
default. It takes effect only after OS unlock while the foreground vault is ready and
is independent of clinical processing restrictions. Screenshots/screen sharing can
include protected information when enabled. Locked/opening screens remain blocked;
pause/lock secures the window, and API 33+ explicitly blocks recents snapshots. Physical
OEM capture/recents acceptance remains unverified. Duration rows are centered and the
Dashboard Settings control uses the standard Material cog. Five-minute authentication
and existing keys are unchanged; longer sessions remain separately under review.
Six new unit cases and a tenth real-device case are added. Screenshot-focused tests
passed 6/6 with zero skips and the test APK compiled offline; the first 5/6 fixture
failure and raw corrected results are retained. Longer-session integration is separately
in progress and still needs fresh coherent checks. Fresh exact-source hosted lint/unit,
all ten current actual API 34 cases and any added session cases remain required. Prior 112-case local results and failed/canceled hosted
runs are retained as earlier-source evidence, not acceptance of this follow-up.
See [scope, exact local results and retained first failures](FIVE_USABILITY_FIXES_2026-10-18.md).
Active chat displays protected feedback and delivery/review errors. Attachments save
evidence notes in Notes/Library; only unchanged submitted fields clear at the durable
local save, including the encrypted RAM draft. Later edits and failed drafts remain.
Check Library for pending/acknowledged/review status. Files are not uploaded into chat
or interpreted by AI. The chat badge reuses check/sync and stale behavior. **Ask again ·
new request** repeats an exact completed original phone text question under its original
minimum privacy and same pairing. It creates a new request, without rewinding context
or retrying uncertain delivery; histories without policy provenance have no action.

## October14 current companion source

See [Local QR pairing](COMPANION_PAIRING_2026-10-14.md). Newsource1.2-dev/code3
adds local secure QR, retains manual/existingpairings and encrypted records,
and preserves five-minute nonexportable-key sessions. Earlier checkpoints below
are historical; finalsource/device/release results remain separate gates.

# Android companion: shared desktop workflows

Implementation checkpoint: 2026-10-05. Based on Android main
`7028ec6d131ffaaf9e03d2f22b3550d7a38ddf3c` and desktop main `c269a18`,
on `chatgpt/shared-companion-workflows-2026-10-05` in both repositories.
These source changes require an updated desktop and a newly built Android APK.
An older installed app does not gain these features automatically.

## Connect

1. Keep the desktop awake, running Private AI, and reachable on an approved private network.
2. On the PC, open Companion settings, enable the HTTPS gateway, and create a
   pairing for this phone. Choose Chat, Workflow and Notifications permissions
   for the features you want. Report-job and clinical-alert metadata are separate permissions.
3. Unlock the Android app using the device screen lock or supported biometric.
   In Settings, paste the complete desktop pairing link and save it. It includes
   the selected private address, per-device credential and certificate proof.
4. Check the connection. Model and hardware values remain unknown when the PC
   does not provide them. A model-unavailable response is an error, never a fake AI answer.

The app requires a matching HTTPS certificate and address. An address-only link,
the old shared example key, plain HTTP and accept-all certificate modes are not
supported. If the address, certificate or device access changes, pair again.
Do not share or publish pairing links. A LAN connection alone does not establish
HIPAA compliance; deployment, provider policy and device controls still require review.

## Conversations and work

Create a chat on the phone or refresh to see phone-owned conversations and PC
conversations explicitly shared with this device when pairing. The PC can open
phone-originated conversations. This does not give the phone unrestricted access
to every desktop chat. History endpoints can return a limited set of records.

Text requests are saved encrypted before delivery and use a durable request ID.
A successful desktop acknowledgment is required before a request is marked
delivered. Retries preserve the same ID. Failed or interrupted work stays pending
or requires review. Requests with an unknown/different destination are not silently
reassigned. A stopped request may have reached the PC; review it before resubmitting.
The destination includes the specific device pairing, so creating a new pairing
on the same PC does not automatically replay the old queue.
Leaving the app for another activity or locking it cancels foreground chat and
upload work. Unlock and check again to retry retained pending requests using
their original IDs. An earlier response cannot be accepted by a later unlock.
The **Stop** control interrupts active requests; future automatic checks remain
enabled while the protected workspace is open. Background reminder polling is
separate and can display only generic notices with its restricted permission.

The phone processing preference adds Local Only and HIPAA requirements to phone
chats. The desktop enforces those requirements during execution and controls
provider approval independently. Turning off this extra phone preference is for
approved general inquiries and cannot remove desktop safeguards. Screen capture is a separate local setting under Privacy & Unlock. It defaults blocked;
allowing it after unlock does not change desktop processing restrictions. Lock/unlock
screens remain protected, and backgrounding restores secure-window protection even
when capture was allowed in the foreground.
Queued requests retain their original privacy restrictions. A stricter setting can
be adopted before the first attempt; after an attempt, changing the immutable
request requires review rather than silently changing its replay identity.
An unavailable AI model does not prevent action/history/status refresh; partial
sync reports the outstanding failures instead of claiming every request succeeded.

## Notes, rounds, reminders and evidence

Open **Notes** and type the observation. Optional location, owner, deadline and draft-channel fields are collapsed under Details. Blank fields remain unknown. Choose an
Outlook or WhatsApp review draft if needed, then save for desktop review.

The PC creates source-linked operational review actions in the same Dashboard.
Review the proposed action, supply missing details and enter a completion note
before marking it complete. An operational completion does not establish a
clinical resolution. Changes use the exact server revision and fail when another
editor has changed the action; refresh before reviewing that version.

Attach JPEG, PNG, PDF or TXT evidence up to 5 MiB. Transfers use bounded chunks,
stable upload IDs, full-file hashes and confirmed receipts. Evidence remains
encrypted on the desktop. **Photos are evidence for human review; this text-model
workflow does not interpret image contents.** A camera preview may have lower
resolution than the original camera image. Unsupported formats fail clearly.

External file/camera pickers can lock the app. Their result stays pending in RAM
until you unlock again and attach it explicitly to the intended workspace.
Unsent chat, capture and manual-note drafts are held as encrypted RAM, not plaintext saved-state bundles.
Process termination can discard unsent forms or pending picker results; saved
notes/requests survive in encrypted storage. Select evidence again after repairing
to a different desktop. A submitted note or evidence preview clears after its
durable phone save when you have not edited it meanwhile. Later network/metadata
failures do not erase that save or imply you should submit a duplicate. The Library
view shows pending and review-required records as well as desktop receipts.
The manual-note **Share** button explicitly exports decrypted text to the Android
app you choose. It is a separate user action outside the protected desktop gateway;
only choose destinations approved for that information.
Capture records display/export a readable summary, with encoded evidence and
private device binding omitted. Large ordinary documents use a clearly marked
16,000-character preview for viewing. **Send preview to chat** uses up to 10,000
characters, further bounded for encoded request size, and explicitly marks partial
content. Ordinary
documents retain **Share original**, up to 200,000 characters for Android's direct
text-sharing transport. Larger exports are refused clearly; use the PC or smaller
sections. Their complete encrypted originals remain stored, without silent truncation.

On Android 12/API 31 or later, **Dictate on device** uses an available on-device
speech recognizer and microphone permission. There is no cloud-recognition
fallback. When unavailable, type the note or attach evidence.

Saving an Outlook/WhatsApp draft stores an internal review item; it does not send
a message or create an Outlook item. Review recipients and content on the PC.
WhatsApp notes-to-actions work depends on the desktop's separately authorized
conversation settings. Read permission does not grant writing permission, and
the disable-all-writing override still applies to delivery.

## Notifications and remaining limits

Pair with Notifications permission, then opt into **Background follow-up notices**
and allow Android notifications. Background work holds only a separate restricted
token for generic reminder IDs and receipts. It cannot access chats, notes,
resident details, files or actions. Generic notices are acknowledged only after
Android accepts their display; this does not complete an action.

Android schedules periodic background checks (normally at least 15 minutes),
subject to battery/network restrictions. This is polling, not instant push.
The desktop must be reachable. Notifications while the PC is locked require a
separate explicit desktop pairing option; the full conversation credential cannot
unlock or bypass a locked desktop.

Report tasks expose job metadata, not facility action controls. Agent/playbook
execution, remote approvals and report-job retry/cancellation are unsupported
through the live companion and fail instead of claiming success. Clinical-alert
metadata is not a resident assessment or an all-clear. Review details on the PC.
Dashboard counts and recent activity are explicitly cached metadata. An empty
cache never means that surveillance ran, that all residents are safe or that a
report completed; current execution and clinical source completeness require
desktop review.

Demo is explicitly labeled and uses a separate database. It never contacts the PC
or supplies simulated successful results to the real workspace. Existing legacy
caches are preserved through additive migration and reviewed separately; queued
requests without an authenticated destination are not automatically replayed.

## Build and validation handoff

Use the checked-in Gradle wrapper with JDK 17 and the configured Android SDK:
`assembleDebug`, `testDebugUnitTest`, and `lintDebug`. Debug APKs are development
artifacts, not a signed production distribution or a device acceptance result.

`GatewayRepositoryTest` covers actual synthetic loopback HTTPS, failure/retry
receipts, certificate/destination checks, exact action revisions, unsupported
operations and evidence replay. It also verifies immutable privacy on retries,
per-device re-pairing isolation, partial sync with an unavailable model, workflow-only
permissions and rejection of mismatched acknowledgments. Crypto/Room tests cover fail-closed storage and
additive migration using explicit synthetic keys rather than production unlock bypasses.
The protected foreground client also binds each request to its OS-unlock and workspace
generation. Locking, re-pairing or stopping cancels active work; an earlier response
cannot become authorized simply because the phone was unlocked again. A two-chunk
HTTPS regression verifies that no next chunk is sent after a lock and that retry
retains the original upload ID and bytes. Restricted background reminders use a
separate notification-only credential and do not unlock the foreground workspace.
`VaultDocumentPreviewTest` verifies that a 5 MiB encoded evidence record has a
bounded readable preview, omits file bytes/private binding, and retains the exact
encrypted original rather than rewriting or deleting it.

`DesktopGatewayInteropTest` runs the Android client against a real isolated Node
Core HTTPS gateway when `PRIVATE_AI_GATEWAY_TEST_PAIR_FILE` names an ignored JSON
file containing `pairUri` (and optional `prompt`/`expectedReplyContains`). It
explicitly skips when that fixture is absent. The fixture must grant Chat,
Workflow and Notifications permissions; it must never contain live accounts/PHI.
It verifies chat replay/history, multi-chunk evidence, capture replay, canonical
action revisions, internal drafts and reminder receipts. Never commit the fixture
or print its credentials.

The 2026-10-05 automated checkpoint passed 45 unit/Robolectric tests with zero
failures, errors or skips, including the real Node Core HTTPS interoperability
test. Its calculator response is not a language-model inference or physical-phone
acceptance result. Final build/artifact and OS acceptance results are recorded in
[Android validation progress](ANDROID_VALIDATION_PROGRESS.md) and the parent
implementation handoff. Physical-phone checks remain necessary for OS authentication/Keystore,
camera/picker reauthentication, local microphone recognition, notification
permission and background delivery. Live WhatsApp/Outlook account and real-data
clinical acceptance are separate checks. Do not claim these passed from compilation
or Robolectric alone.


## Simplified companion — October 11, 2026

Chat opens by default. Dashboard is the single overview; Notes, Meetings and Library
are the other primary destinations. Settings contains connection and privacy controls.
Settings → Appearance saves Follow system, Light or Dark on this phone, including the
locked screen. This non-sensitive preference is separate from protected workspace data.

A compact connection badge and last successful sync time appear on the main screens.
Green means a recent successful connection check; red indicates disconnected; amber
means checking/syncing or a check older than 90 seconds. Tap to check/sync. Last sync
means a fully successful refresh/delivery pass, not a partial result. Its encrypted
receipt survives reopening for the same pairing, and is cleared from view when locked,
in Demo or after re-pairing. Cached time never proves the PC is currently online.

In Meetings, create a recording, allow microphone access and keep the screen open and
phone unlocked. Native PCM recording does not use an online speech service. Audio is
saved in encrypted bounded chunks; the paired PC performs offline transcription and
prepares draft minutes and follow-ups. Missing PC speech assets leave the recording
pending. The desktop requires a one-time verified runtime/model download before offline
transcription. Review transcript accuracy, deadlines and speakers before acting.

Pause/Resume retains committed audio. Finish waits for the exact acknowledged audio
count, then requests desktop processing. Lock, navigation, backgrounding and authentication
timeout stop recording; up to 20 seconds not yet committed can be lost. There is no
background microphone service or authentication bypass. A recording is limited to 60
minutes; 512 MiB of pending audio is allowed. Automatic speaker diarization is not
implemented; desktop attribution review remains available.

The wire extension is documented in the desktop `ANDROID_COMPANION_CONTRACT.md`.
It requires the `meetings` capability and Workflow scope; stable IDs, per-pairing replay,
SHA-256 acknowledgments, certificate validation and encrypted pending records remain.
A reused bounded HTTP connection pool prevents bursts of audio/chat/refresh requests
from exhausting the desktop's 24-connection limit. Certificate validity is still checked
for every request, including when the pool is reused.

Every Outlook/WhatsApp send or delete requires desktop/user confirmation. Outgoing
WhatsApp results and reminders wait for approval; the phone cannot approve them remotely.
Internal drafts and operational completion are not external sends or clinical closure.
