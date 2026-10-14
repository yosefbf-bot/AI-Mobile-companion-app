# Companion 1.2-dev — local QR pairing

This source update adds on-device QR scanning for the existing desktop gateway.
It preserves your previous pairing, encrypted records, app ID and local signing
identity. Updating source does not install a new APK or establish physical-phone
acceptance.

On the PC, use Settings → Android Companion Gateway: enable the actual approved
private interface, then Pair a phone with QR. Name the phone and select only
the access you want. Keep the PC awake/reachable. No firewall or VPN changes are
automatic. On the unlocked phone, use Settings → Desktop connection → Scan desktop
QR. Camera permission is requested only then. Manual invitation paste and legacy
certificate-bearing links remain available.

The PC invitation expires after three minutes and can be used once. The phone
verifies the leaf fingerprint, certificate purpose, dates and private IP identity
during TLS before sending the token. No redirects, automatic exchange replay or
remote scanning service is used. Approved durable credentials are saved encrypted
only after an actual authenticated status response and current session/config
checks. Connected means that check succeeded, not merely that scanning succeeded.

If a response is lost after the PC created a device, inspect Paired phones on the
PC, revoke the incomplete device and make another invitation. Failed replacement
keeps the old phone configuration. Closing a successfully completed wizard does
not revoke that phone. Existing queued work remains bound to its original pairing
and privacy settings. Legacy credentials are reusable until explicitly revoked.

The camera preview/analyzer belongs to the visible unlocked scan screen. It uses
CameraX1.5.0 and on-device ZXing core3.5.4 (Apache2), closes each frame and releases
owned camera/executor resources on decode, cancel, navigation, lock or background.
No frame or scanned token is saved in Room, the outbox or saved UI state.

Privacy & unlock explains the existing five-minute encrypted session. Its age is
monotonic and starts at the actual Android authentication callback; user activity
or wall-clock changes do not extend it. Lock now/background/phone lock and all
transport/crypto epoch checks remain. Ten/twenty/thirty-minute and Off modes are
disabled because the existing nonexportable key has immutable300-second access.
No existing key is replaced or migrated. Longer sessions require an explicitly
authorized reviewed additive migration and recovery/device acceptance.

17 focused bootstrap tests and two actual Kotlin→Node Core HTTPS interoperability
cases passed; ten lease/decoder cases passed, including the actual Edge-rendered
synthetic QR fixture. Final full local build/unit/lint/signer and current-head
hosted six-case instrumented results are separate pending gates. The two new
instrumented cases cover actual camera permission denial/manual fallback and
real300-second session/OS-key expiry. Compiling these tests is not executing them.
Physical camera scanning, microphone, Wi-Fi/VPN, OEM/background notification and
signed existing-phone upgrade still require device acceptance.

Desktop wire/architecture instructions: docs/COMPANION_PAIRING_2026-10-14.md in
Private-AI-OG-google-version. Ordinary mobile protocol1 remains compatible; v2
refers only to the additive invitation bootstrap.
