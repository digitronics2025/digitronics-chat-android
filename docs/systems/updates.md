# App updates (signed manifest)

> Last verified: 2026-11-09 — first version. Server: whatsapp-inbox-saas `docs/systems/android-releases.md`, `worker/src/lib/release-manifest.ts`.

## Check
`GET /api/v1/mobile/updates/check` (with `X-App-Version-Code`) from: the App
updates screen, a 12-hour periodic WorkManager job (network required), and an
`app_release_available` push hint.

The pinned key defaults to the production key (key id `k1`; public half of the
Worker secret `ANDROID_MANIFEST_SIGNING_JWK`) in debug and release builds.
`-PMANIFEST_PUBLIC_KEY_X509_B64=…` / `-PMANIFEST_KEY_ID=…` override it for a
rotation. An empty or unusable key turns checking off and the screen says
"Update checking is not configured in this build". The last answer is stored and re-verified on launch.

## Verification (`ManifestVerifier`)
1. A key must be pinned (base64 X.509 SPKI, EC P-256); an offer whose `keyId`
   names a different key than the pinned one is refused.
2. `signature` must be present: base64url DER ECDSA over SHA-256
   (`SHA256withECDSA`) of `CanonicalJson.encode(manifest)` — the raw JSON
   object as received, so unknown fields are covered.
3. Then, from signed fields only: `schemaVersion == 1`, `packageId ==
   com.digitronics.chat`, `expiresAt` in the future, `versionCode >` installed,
   `deviceId` present and equal to this device, `canonicalDownloadUrl` https on
   the app's own host.

`CanonicalJson` ports the Worker's `canonicalJson` (keys sorted by UTF-16 code
unit, `JSON.stringify` escaping, JavaScript number printing). The unit tests
compare against vectors printed by the TypeScript function and verify a
manifest signed by the Worker's own `signManifest`.

## States
NotConfigured, NeverChecked, Checking, UpToDate, NoRelease,
UnsupportedNoOffer (below the minimum but nothing offered), Available
(`mandatory` = server flag OR installed < signed `minSupportedVersionCode`),
Rejected(reason), Offline, Failed.

## Install
P0 opens `canonicalDownloadUrl` in the browser; Android's installer asks for
approval. No `REQUEST_INSTALL_PACKAGES`, no in-app download.
