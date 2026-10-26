# Companion idle-resource checkpoint — 2026-10-26

Base: `c5ef833` (main). No protocol, dependency, version, schema, signer or installed-data changes.

The seven protected Room streams now cancel their database subscriptions while
the vault is unavailable. Readiness, selected database and workspace generation
jointly restart them, including a successful pairing that keeps the same database.
Existing privacy/readiness checks remain at decoding time.

Locking evicts only draft cache entries whose exact current revision is committed
and whose write has no pending/in-flight/error state. Referenced attachment cache
entries are released with them. Unsaved/autosave-off/failed writes remain intact;
saved drafts reload from encrypted Room. A save finishing after lock also trims
its durable cache. A stale load cannot disable this trimming after lock.

New regression scope: no record-table reads from locked collectors; reload after
unlock; saved attachment eviction and exact reload; pending save durability;
load/lock race; active collector surviving real synthetic HTTPS pairing.

Independent read-only review found two generation/lock races, both corrected and
rechecked. `git diff --check` passed. Local targeted Gradle could not reach
compilation: JDK17 failed with `Unable to establish loopback connection`.
Hosted full build/unit/lint/instrumentation and Windows HTTPS interoperability
remain required before merge. Results will be appended after that run.

No measured whole-process RAM or battery improvement is claimed. Tests measure
subscription reads and retained encrypted-draft bytes. Physical phone background
delivery, OS process eviction and device upgrade checks remain unperformed here.
