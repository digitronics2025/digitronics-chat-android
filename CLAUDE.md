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
