# Private AI Android companion instructions

GitHub is authoritative: `https://github.com/yosefbf-bot/AI-Mobile-companion-app`.
Before every implementation task, fetch and verify `origin/main`, the active
branch, worktree changes and version in `app/build.gradle.kts`. Preserve local
work. Use a separate `chatgpt/*` branch and a review pull request; the owner merges
main. Recheck main before publication. Do not claim an unchecked local copy is
current. If connector access fails, ordinary authorized Git access may still work.

Read `AI_HANDOFF.md` and `docs/SHARED_DESKTOP_WORKFLOWS.md`. The desktop repository
is `https://github.com/yosefbf-bot/Private-AI-OG-google-version`; its
`docs/ANDROID_COMPANION_CONTRACT.md` defines the wire contract. Inspect both latest
architectures before changing the protocol; do not replace them with a sample
bridge script or historical export.

Preserve the application ID, signing compatibility, existing records and additive
Room/content migrations. Never use destructive migration or plaintext encryption
fallback to pass tests. Full vault access requires Android OS authentication and
Keystore encryption. Never add a production test unlock, application PIN bypass,
static shared pairing key or accept-all TLS. The optional background worker holds
only the independent restricted notification credential and generic metadata.

Real mode uses authenticated, certificate-verified desktop responses. Demo is
explicitly labeled and isolated. Missing telemetry, models, dates, owners and
clinical findings remain unknown. Photos are review evidence unless a separately
implemented and validated image-analysis capability is available. Operational
completion is not clinical resolution; internal drafts are not sent messages.

Persist request identity and privacy requirements before network attempts. Keep
retries bound to the same device pairing and input; stricter current policy must
not weaken prior privacy or change an already attempted replay. Failures and
partial synchronization must be visible, without fabricated success.

Run the Gradle wrapper build, unit tests and lint, plus applicable real HTTPS
interoperability and Android instrumented tests. Report skips/failures honestly.
Compilation and Robolectric do not establish physical-phone Keystore, camera,
microphone, notification, LAN or signing acceptance. Update the handoff with exact
commit/test/artifact scope. Keep `.env`, pairing links, signing keys, databases,
user evidence, SDKs, caches and APKs out of source control. The explicitly named
synthetic gateway test certificate is a public fixture, never a production key.
