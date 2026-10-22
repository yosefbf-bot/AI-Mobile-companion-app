# API34 AtomicFile host-test portability

The full Windows/JDK17 Robolectric run exposed six existing test failures in
StorageBoundaryTest, VaultPolicyPreparationTest and VaultPolicyStorageTest.
Their source and production crypto were unchanged from accepted main71e1c9f.
Initial writes succeeded; replacing an existing committed base or recovering a
backup over an existing base failed the unchanged read-back verification.

The cached API34 Android AtomicFile implementation calls File.renameTo for
both replacement paths. An actual synthetic host probe confirmed Windows
File.renameTo refuses an existing destination while same-directory
Files.move(ATOMIC_MOVE, REPLACE_EXISTING) replaces it. This differs from Android
filesystem behavior. The failed run and independent diagnosis are retained in
the coordinator's local validation evidence; no owner files were involved.

Only the three affected API34 Robolectric test classes opt into
AndroidAtomicFileShadow. It intercepts only the private rename helper, retaining
real AtomicFile start/finish/fail/read/recovery and all existing assertions.
It keeps directory handling and failure logging, uses atomic replacement with
no copy/non-atomic fallback, and never manufactures a successful commit.
Production crypto, encryption, journal/state checks, fsync and APK code are
unchanged. The shadow lives in src/test and cannot enter application variants.

The causal focused rerun passed all10 cases, zero failures/errors/skips. The
complete current local unit run executed178:176 passed, zero failures/errors,
and the two explicitly named external-desktop interoperability cases skipped
because that isolated fixture was not running. Those two remain mandatory with
zero skips in the separate hosted Windows job. Existing actual Android emulator
cases remain unchanged. Current full hosted acceptance is still required.

Local lint separately found a malformed ignored SDK property and reused its
stale analysis. The local path/line ending is corrected and analysis explicitly
re-executed; no lint rule is suppressed or repository build policy weakened.
