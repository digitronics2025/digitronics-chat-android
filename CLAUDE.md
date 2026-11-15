# CLAUDE.md — digitronics-chat-android

Engineering rules for the Digitronics Chat Android app.

## Boundaries
- This repository **builds, signs and uploads drafts**. It never publishes a
  release, deploys a server, or edits server data. Publishing is a human
  decision on the website.
- Server contracts live in **digitronics2025/whatsapp-inbox-saas**:
  `packages/shared/src/mobile.ts` (auth, devices, push, updates) and
  `packages/shared/src/schemas.ts` (DM). Mirror them in `net/Dto.kt`; never
  invent a field. The server only adds fields, so decoding ignores unknown keys.
- The app may call only routes on the Worker's `MOBILE_ROUTE_ALLOWLIST`
  (`worker/src/lib/mobile-auth.ts`). Anything else answers as unauthenticated.

## Secrets
- Never commit a keystore, a password, `google-services.json`, a token or a
  private key. Signing comes from environment/Gradle properties only.
- Firebase client values are build inputs with empty defaults; empty means
  push is off, never a placeholder value.
- The update-manifest PUBLIC key (key id `k1`) is pinned by default in
  `app/build.gradle.kts`; its private half is a Worker secret this repo never
  sees. Rotate by overriding `MANIFEST_PUBLIC_KEY_X509_B64` / `MANIFEST_KEY_ID`.
- Never log or print tokens, FCM tokens or message text. Diagnostics go
  through `diag/Diagnostics.kt`, which redacts.

## Security rules that tests pin
- Refresh tokens rotate: every refresh goes through `TokenRefresher` (one
  Mutex). A second use of a refresh token revokes the device server-side.
- `ApiClient` executes synchronously on Dispatchers.IO; never switch it back to
  `enqueue` (the 401 authenticator would deadlock OkHttp's per-host limit).
- The update manifest is verified over `CanonicalJson.encode` of the raw JSON
  (never a re-encoded DTO). If the Worker's `canonicalJson` changes, regenerate
  `app/src/test/resources/vectors/` from it.
- Access token in memory only; refresh token sealed by the Android Keystore
  (`KeystoreCipher`); backups disabled.

## Working
- Gate: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`.
- Update the matching `docs/systems/*.md` in the same change as the behaviour.
- Conventional Commits. Strings in `res/values/strings.xml`; layouts use
  start/end (RTL-safe).

<!-- BEGIN operator-conventions: regenerated from digitronics2025/claude-config -->
## Reply contract — every task-closing reply

End every task-closing reply with these three, in order, nothing after:

1. **Plain recap**, 3 sentences, no paths/hashes/jargon ("saved" not "commit",
   "sent live" not "deploy"); shipped work may use the five-part form
   (changed / was wrong / before→after / gain / next), ~180 words.
2. **Grandma summary**, 2-4 plain sentences, no tool, file or product names —
   what it means for the shop. Unverified stays unverified.
3. **What you need to do** — numbered steps naming the exact button and what
   they should see, or exactly `Nothing — you're all set.` Never empty, never
   "just". Anything the user must do goes ONLY here.

"skip recap" silences 1 and 2; 3 stays. Not for technical answers, code, plans or
commit messages.

Prefer **maintainable > scalable > secure > production-ready > sustainable**.
Never report unverified work as done: checked, not verified, or could not check.
Update the doc owning a behaviour in the same commit. Never commit a secret.
Only this repo's tracked files and account-level skills reach a session off the
operator's PC — never point at `~/.claude/...` or `C:\Users\...` unmarked.

**Secrets an agent creates go into MyVault first** — every agent, every tool.
A password, key or token you create for an app is saved in MyVault before it is
written anywhere, and its value never appears in output, a file, a chat or a
commit. With `MYVAULT_DEPOSIT` set in the environment, use MyVault's own tool:

```bash
V=https://myvault.digitronics-electro.workers.dev/downloads
curl -fsSL "$V/vault-deposit.mjs" -o vault-deposit.mjs
curl -fsSL "$V/vault-deposit.json" | grep -q "$(sha256sum vault-deposit.mjs | cut -d' ' -f1)" || { echo "checksum mismatch: stop"; exit 1; }
node vault-deposit.mjs check
node vault-deposit.mjs create --name "<App> — <what it is>" --target "<where it goes>" --then "<this repo's write command, reading stdin>"
```

Tell the operator to unlock MyVault; the value is handed on only after the
vault confirms the save. Exit 0 = saved and written; 5 = saved, but writing it
failed (the operator copies it from MyVault); anything else = not written
anywhere (`node vault-deposit.mjs --help` explains each code). Report it; never
make the value some other way. Without `MYVAULT_DEPOSIT`, stop and ask the
operator to save it in MyVault by hand. Never decode or print `MYVAULT_DEPOSIT`.
Where the AI Development Control Center is available, it comes first (Claude: the
`secret-custody` skill).

**Merge your own PR once it is green — without asking.** Operator's standing
order (2026-09-29), every repo and session. Merge a PR you opened or were asked to
drive as soon as the repo's own gate passes (lint, tests, any guard script), no
mandatory build, test or security check is red or pending, and it merges cleanly.
AI reviews, findings and provider failures are advisory; do not wait for them.
Required human approvals remain mandatory.
Otherwise fix it or report. Never merge a red, untested or conflicted PR, one that
needs a manual step first (remote migration, secret, Worker deploy), or one you
only watch. Where a merge deploys to production, say so in the reply.
Claude is primary, with Codex fallback. Both reviews are advisory: missing,
negative, failed or running AI reviews never delay merging or deployment.

Conditional rules (branch model, approval, autopilot, machine-bound skills):
[digitronics2025/claude-config](https://github.com/digitronics2025/claude-config)
<!-- END operator-conventions sha256:465c79fc0dcb -->
