# Android candidate validation and resume notes

Candidate: application ID `com.aistudio.privateai.kq7zwa`, version code **2**, version name **1.1-dev**. Based on verified Android `origin/main` commit `7028ec6d131ffaaf9e03d2f22b3550d7a38ddf3c`, on branch `chatgpt/shared-companion-workflows-2026-10-05`.

## Observed results on October 5, 2026

- Genuine Gradle 9.3.1 wrapper restored; wrapper JAR and distribution have official SHA-256 checksums.
- Portable official JDK 17 and Android SDK 36/36.1 actually executed on Windows. No global installation or private user profile was changed.
- An earlier debug APK was packaged and an earlier 31-test checkpoint passed, including the real desktop HTTPS connection. Later source changes require their own final build evidence.
- The final frozen-source **45-test suite passed with zero failures, errors, or skips** across six classes. `DesktopGatewayInteropTest` actually connected the Android client to an isolated running Node Core gateway using pinned HTTPS, and verified chat replay/history, evidence transfer, captures, actions, drafts, and reminder receipts. The final run started at 19:25:02 UTC and its interoperability case took 14.676 seconds. It used the deterministic local calculator; it is not a new language-model inference test.
- Regression coverage includes authenticated encryption/tamper rejection, fail-closed application access, v2/v3 Room upgrades, rollback on corrupt legacy data, preserved historical/synthetic records, immutable outbox privacy flags, pairing-bound retries, durable capture receipts, and bounded document previews.
- A complete prior checkpoint passed `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest`, with **zero lint errors and 72 warnings**. Explicit AndroidX Fragment 1.8.9 fixed the five ActivityResult dependency errors. Instrumentation compilation also caught and corrected an invalid Compose member import. Intermediate mixed-source checkpoint failures were resolved by rebuilding the changed production code before its tests.
- The final production compile and 45 tests include authentication/workspace request leases, chat size/Unicode limits, and truthful Dashboard/Tasks cache labels. The complete final command `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` finished **BUILD SUCCESSFUL in 6m 16s**, with **zero lint errors and 72 warnings**. Warnings concern dependency versions, unused resources, launcher icons, and minor catalog/label/KTX suggestions. No lint security gate was suppressed. **No emulator or physical-phone acceptance pass is claimed yet.**

Final test classes: `FeatureErrorTesting` 18; `GatewayRepositoryTest` 18; `VaultDocumentPreviewTest` 3; `ExampleRobolectricTest` 4; `ExampleUnitTest` 1; `DesktopGatewayInteropTest` 1.

The 84 production/build input files have normalized source digest `1fa5e276dd9364434cbc59f61a1a89d4c9dc8df299411f97461d209f765ef199`, verified unchanged before and after the final build. This hashes the sorted relative UTF-8 path, NUL, raw SHA-256 of each file, and LF; CRLF is normalized in Kotlin, XML, TOML, properties, ProGuard, and example text. Inputs comprise `app/src/main`, root/app build scripts, Gradle properties/catalog/wrapper files, ProGuard rules, and `.env.example`. No private `.env` was present. The Git source commit is assigned by publication; documentation changes are outside these production inputs.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 28,167,895 | `6d684203f74a7558a8f39aa2a00a74a3c97cbbcd1760c811f1bb5e9a6a4fa82e` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 1,098,093 | `2aa76b622e14eaf8fb53d05bd2bcbe56a0e5aaa22e8e398eb963b27ab45c63ed` |

`apksigner verify` succeeds for the actual app APK. Its **Android Debug** certificate SHA-256 is `4f2f15654f1730b6b27a942464629d05b8388c5f0c7fa60863c3ffff6c435fbd`. This is development signing, with no claim of matching a prior installed/release certificate.

## Resume the local validation

The workspace sibling `PrivateAI-Android-Tooling` contains the verified JDK, Android SDK, Gradle cache, Android tooling user directory, and download checksum evidence. `toolchain-paths.json` records the absolute paths. Use these paths only locally; do not include the caches, debug keystore, device profiles, or pairing fixture in a source archive.

Set `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_USER_HOME`, and `GRADLE_USER_HOME` to those directories. On this Windows environment, Gradle's local IPC also requires `JAVA_TOOL_OPTIONS` to set `jdk.net.unixdomain.tmpdir` to the short workspace `_androidtmp` directory. This avoids the observed Windows AF_UNIX temporary-path failure and does not disable network protection.

The local final build is finished, and no owned Java build process remains. Do not repeat the completed local suite without a new source change or a specific unresolved concern. The `PRIVATE_AI_GATEWAY_TEST_PAIR_FILE` environment variable used an ignored synthetic desktop fixture created by the parent task. Do not print or publish that file. For a new interoperability run the desktop test server must be running; create a new isolated fixture rather than using a real account if necessary.

The remaining automated acceptance is the GitHub workflow's isolated Android emulator lock/startup and successful OS-credential/Keystore tests. Its known PIN exists only on the disposable CI emulator, with no production bypass. It uploads reports and captures failure evidence from that emulator. Initial published head `56e6796` run `37393737660` failed before building or testing because `sdkmanager` was not on the hosted runner's PATH (exit 127); all build and device checks were skipped. The workflow now explicitly prepares pinned Android command-line tools and exports the installed emulator directory. This CI-only fix does not change the APK's production input digest. Inspect the review PR's exact new head for the executed result; no emulator pass is inferred from the repair. The actual desktop interoperability test deliberately skips in CI when its isolated desktop fixture is absent; the local final run executed it, with no skipped tests. No physical Android device was attached during local validation.

## Required acceptance still distinct from compiler/unit tests

Hosted check `e853a86`, PR run `37395427311`, passed SDK setup, app/test APK
build, unit tests and lint. Its reports contain 44 passing unit cases and one
explicitly skipped external desktop-fixture case, no failures/errors, and 74
lint warnings with no errors. Emulator startup failed with **Unknown AVD name**:
creation and launch resolved different AVD directories. OS-authentication tests
were skipped; no device pass is claimed from that run. The workflow now uses one
explicit disposable AVD directory, verifies its INI/list entry, bounds startup,
and retains launch diagnostics even without an attached device. The positive
OS-authentication test also scrolls to the lazy dashboard card before checking
its visibility, so a smaller screen cannot produce a false missing-card failure.
These changes affect CI/test/documentation only; the production input digest and
local APK above are unchanged. Inspect the subsequent exact-head run for actual
device test execution and counts.

Subsequent source `1f87b07`, push run `37396619221`, found the AVD but failed
before Android boot because its default userdata partition needed 7,372.80 MB
while the hosted runner had only 5,848.78 MB available. Build/unit/lint still
passed with the same 44-pass/one-fixture-skip scope. No OS-authentication pass is
claimed. The disposable AVD now explicitly uses a 2 GiB userdata partition and
the documented 2,048 MB emulator partition option. No phone/app data is deleted
or resized; this changes only the newly created, account-free CI test device.

- Real Android OS authentication successfully unlocks an existing/new Keystore vault; cancellation, lock, backgrounding, and five-minute expiry close it.
- Existing v2/v3 vault upgrades preserve data, migrate the retired encryption, label old synthetic/unverified caches, and retain any unpaired queue for review. Corruption/unsupported schemas must preserve the original database.
- External photo/file selection survives the authentication gate, restores the encrypted unsent draft, and remains an unsent preview until explicitly submitted. Pairing changes must not redirect pending evidence.
- Microphone transcription uses available on-device Android recognition only; unsupported phones show an unavailable state rather than a cloud fallback.
- Optional background notices use the separate revocable notification-only key, pinned HTTPS, Android notification permission, and generic text. The full chat/workflow key remains OS-authentication-bound. Permission/network/desktop-lock failures must not mark a notice delivered. Display acknowledgment does not complete an action.
- Test pairing, revocation, offline outbox replay, duplicate requests, actual shared history, evidence review, action revisions, internal drafts, and follow-up acknowledgments with a real running desktop gateway.

## Signing and data safety

The normal Android toolchain now generates its local debug key; the missing custom root debug keystore is no longer required. A debug APK is a development candidate. It cannot be represented as an upgrade over an existing APK signed with a different certificate. Preserve the original release signing key for a real upgrade, or use an explicit, separately reviewed migration/install plan. The app ID and Room data are retained; no destructive migration or automatic wipe exists.

The app excludes protected storage from cloud/device-transfer backups and denies cleartext traffic. Saved content/metadata and complete pairing settings use authenticated Keystore encryption. Retired encryption is decoded only during a rollback-safe authenticated upgrade; it is never a fallback for current encryption or unlocking. Old synthetic records are retained and explicitly labeled, not presented as current clinical findings or performed desktop work.
