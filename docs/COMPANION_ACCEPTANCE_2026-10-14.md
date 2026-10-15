# October 15 current companion acceptance checkpoint

Current source is `1.2-dev`, code `3`, on
`chatgpt/companion-pairing-2026-10-14`, from verified main
`7e817069468a5106f35a6908e916dad92e72e1ef`. The production repository changed:
locking now publishes the closed workspace and advances its generation before
cancelling transport/jobs. Unlock/configuration workers check their generation
and publish readiness under the same existing commit guard. Stale workers cannot
reopen a revoked workspace. Two deterministic controlled-dispatcher regressions
cover cancellation observers and queued/reentrant unlocks.

The final local full suite passed **95/95**, with **zero failures, errors or
skips**, including both actual Kotlin-to-desktop Core/SQLite/pinned-HTTPS
interoperability cases (legacy and v2 pairing). Focused bootstrap coverage passed
19/19. Both APKs built, the frozen camera test compiled, and final lint passed
with **zero errors / 43 warnings**. The camera acceptance fixture now advances
its actual Compose scheduler before checking bounded scroll progress; all
visibility, OS-permission and lifecycle assertions remain required.

Actual APK SHA-256 values:

- App: `a8312afcbb8f9615610bd768c53b82f616e55155737188f5fe7667e4082b7563`
- Instrumented test: `e7a357ce0ce59582f363a018a39da10a567fcb619f579b505754fb65a42e7092`

Both verify with the existing signer
`4f2f15654f1730b6b27a942464629d05b8388c5f0c7fa60863c3ffff6c435fbd`.
The app ID, encrypted records, pairing format, immutable 300-second Keystore
configuration and signing compatibility are unchanged. The production APK is
**new**; earlier unchanged-APK statements below describe historical checkpoints.

Fresh exact-head hosted execution of **all six instrumented cases** remains
pending and is required before PR4 merges. Prior `347a128` evidence is not
acceptance for this new source: its push exposed the repository cancellation
ordering race, and its PR device run passed five cases but stopped in the camera
fixture's scheduler/progress assertion. First failure XML/logs remain preserved,
including the initial full95 regression-helper completion-wakeup failure; that
helper now wakes on actual job completion instead of expecting another dispatch.
No security assertions were removed. Local compilation does not establish
physical-phone camera, microphone, LAN/VPN, background-notification, OEM or
signing-compatible upgrade acceptance. Owner apps, data, keys and pairings were
not installed over or changed. The isolated synthetic gateway is closed and
this validation's Gradle daemons are stopped.

## Historical October 14 checkpoints

### October 14 companion acceptance checkpoint

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
