# October 14 companion acceptance checkpoint

Current source is 1.2-dev, code 3, on the reviewed pairing branch from
main `7e817069468a5106f35a6908e916dad92e72e1ef`. Production APK remains
`c59247c71d8faf990af853b0ba6416696b7e1a9ffe5194db68518595c6f93bbb`;
the existing signing identity, app ID, encrypted records and immutable
300-second Keystore key configuration are unchanged.

The full local suite passed 93 tests with zero failures, errors or skips,
including two real Kotlin-to-desktop Core/SQLite/pinned-HTTPS interoperability
cases. App/test builds succeeded. Lint: zero errors, 43 warnings.

Both first hosted runs on `bf4e907` failed: only three of six instrumented
cases were attempted, one passed and two failed. Their logs and XML remain
preserved. The raw Keystore-expiry assertion ran too close to the platform's
whole-second boundary, and recursive Compose scrolling later exhausted the
192 MB emulator heap. These are test corrections, not weakened security gates.

[Android 14 AOSP enforcement](https://android.googlesource.com/platform/system/security/+/refs/tags/android-14.0.0_r1/keystore2/src/enforcements.rs)
rejects when integer token age exceeds 300 seconds.
[The timestamp implementation](https://android.googlesource.com/platform/system/security/+/refs/tags/android-14.0.0_r1/keystore2/src/database.rs)
calculates seconds using integer milliseconds divided by 1000. The corrected
case waits a bounded two seconds after the app lease closes, verifies the
app remains locked with no new OS authentication, and retains both raw
`UserNotAuthenticatedException` assertions. Known fixture indices replace
recursive scrolling while retaining exact UI, permission and lifecycle checks.
No production timer, cryptography, heap allocation or key policy changed.

After these corrections, 14 focused unit tests passed with zero failures,
errors or skips; instrumented compilation, test APK assembly and lint passed.
Test APK SHA-256:
`1833a26b5288622c9d939cc6987fbfc165e1434f11f80e551d5c11e9f003bf83`.

Fresh exact-head hosted acceptance must still run all six instrumented cases
with zero failures and skips before PR4 can merge. Physical phone camera,
microphone, LAN/VPN, background notifications, OEM behavior and signing-compatible
upgrade remain separate device acceptance; compilation cannot establish them.
Owner installations, data, keys and pairings remain untouched.
