# Companion 1.2.1-dev — retained chat preview unlock correction

Base: authoritative Android main `ffa762a1495051449bd995d165dd8f7f7f50ac6f`.
Review branch: `chatgpt/companion-auth-loop-2026-10-15`.
Application version is `1.2.1-dev`, version code `4`; application ID is unchanged.

## Confirmed cause and narrow change

The owner reported successful desktop pairing followed by repeated authentication
with both face unlock and the device PIN. Source inspection identified a common
post-authentication failure independent of those two OS authentication methods:

1. Desktop conversation synchronization creates a stored chat with no preview.
   `EncryptedChatSession.lastMessagePreviewEncrypted` legitimately defaults to
   the exact empty string. Newly created local chats can have the same absence.
2. The initial Chat list unconditionally passed that absence to its decryption
   callback. The authenticated vault codec correctly rejects an empty string as
   invalid ciphertext.
3. The production decryption error path correctly revokes application access.
   Rendering the retained chat again after another successful unlock repeats the
   failure. The existing unpaired/empty-list device acceptance did not cover it.

Only the Chat list now treats the exact empty preview sentinel as no preview.
Every nonempty value still follows the original strict authenticated decoder,
including whitespace, malformed values and valid ciphertext for an empty body.
No decryption fallback, OS authentication change, key recreation, data migration,
pairing reset, session extension or privacy change is introduced.

Other decryption callers were checked for this same optional-empty condition.
Message/document bodies are written as ciphertext; optional attachment data is
nullable. No second confirmed empty-sentinel caller was identified.

## Regression and acceptance scope

Three new real Compose/strict-production-codec unit cases cover populated new and
desktop-shared chat rows with no preview, retained authenticated previews
(including encrypted empty text), and malformed/whitespace rejection.

A seventh instrumented case uses the real Android OS credential and Keystore,
then inserts only a synthetic encrypted retained shared-chat cache row with the
same absent-preview shape. It requires three unlock/list/open/back cycles to keep
the original ciphertext and authorization generation, and finally verifies that a
malformed nonempty preview still locks the vault. It does not use a live desktop
pairing or claim physical-phone acceptance. Cleanup removes only its synthetic ID.

At implementation checkpoint, independent source review passed and `git diff
--check` passed. New Gradle/unit/lint/device results are not yet claimed: the
owner PC had under 1 GiB of available RAM before a build began. Current exact-head
hosted checks and a same-signer APK build remain required before distribution.
The previous 95 local / six hosted device results apply to the prior version,
not automatically to this patch.

## Updating the existing phone

Keep the existing app installed and preserve its data. Once the corrected APK is
accepted, install the `1.2.1-dev` update signed with the same existing certificate;
Android should update the app without deleting chats or the saved pairing. Do not
uninstall, clear storage or remove the phone screen lock to work around this bug.
If Android reports a signing conflict, obtain the matching signed update instead
of deleting the existing application. Actual owner-phone verification remains a
separate acceptance step after the tested APK is available.
