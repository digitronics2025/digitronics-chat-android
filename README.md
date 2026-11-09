# Digitronics Chat (Android)

The native Android app for DigiTronics team messaging: private 1:1 and group
chats, bot report cards, push notifications and in-app update checks, signed in
through the Digitronics Messenger website. Package `com.digitronics.chat`
(debug builds: `com.digitronics.chat.debug`, named "Digitronics Chat Debug").

The server half lives in **digitronics2025/whatsapp-inbox-saas**: the Worker at
`https://messenger.digitronics.app`, with every wire contract in
`packages/shared/src/mobile.ts` (mobile auth, devices, push, updates) and
`packages/shared/src/schemas.ts` (direct messages). This repository only builds,
signs and uploads **drafts**; it never publishes a release.

## What is in the first version

| Area | What it does | Notes |
| --- | --- | --- |
| Sign-in | PKCE S256 in a Custom Tab, App Link callback, device-bound tokens | [docs/systems/auth.md](docs/systems/auth.md) |
| Chats | List with All / Unread / Groups / Bots, threads, bubbles, day separators, read ticks, bot report cards, text send with retry-safe ids, delta sync, realtime socket | [docs/systems/sync.md](docs/systems/sync.md) |
| Notifications | FCM without the google-services plugin; Messages / Bot reports / App updates channels | [docs/systems/notifications.md](docs/systems/notifications.md) |
| Updates | Signed manifest check (ECDSA P-256), browser download | [docs/systems/updates.md](docs/systems/updates.md) |
| Settings | Account, Notifications, Appearance, App updates, About & Developer, Diagnostics | |

Not in this version: media upload or viewing (attachments show a label and
point to the browser chat), new chats and groups, reactions, replies, typing
and presence display, in-app APK install.

## Build

Requirements: JDK 21 and the Android SDK (`platforms;android-35`,
`build-tools;35.0.0`). Point Gradle at the SDK with `sdk.dir` in
`local.properties` or `ANDROID_HOME`.

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Outputs: `app/build/outputs/apk/debug/app-debug.apk` and
`app/build/outputs/apk/release/app-release-unsigned.apk` (or `app-release.apk`
when signing is configured, below).

Toolchain pins (`gradle/libs.versions.toml`): Gradle 8.14.3, Android Gradle
Plugin 8.13, Kotlin 2.2 with the Compose compiler plugin, Compose BOM 2025.06.01.
AndroidX libraries are held at the last versions that compile against SDK 35;
moving to SDK 36 is the moment to raise them.

### Build-time values

Each value is read from `local.properties`, then a Gradle property (`-PNAME=…`),
then the environment.

| Name | Effect when empty |
| --- | --- |
| `FIREBASE_PROJECT_ID`, `FIREBASE_APP_ID`, `FIREBASE_API_KEY`, `FIREBASE_SENDER_ID` | Push is off; Settings says "Notifications not available in this build". |
| `MANIFEST_PUBLIC_KEY_X509_B64` | **Defaults to the production key** (public half of the Worker's `ANDROID_MANIFEST_SIGNING_JWK`, base64 X.509 SubjectPublicKeyInfo, key id `k1`) in both debug and release. Override to rotate; an empty or unusable value turns update checks off ("Update checking is not configured in this build"). |
| `MANIFEST_KEY_ID` | Defaults to `k1`; an offer whose `keyId` names another key is refused. |
| `VERSION_CODE`, `VERSION_NAME` | Default `1` and `1.0.0`. |
| `CHAT_BASE_URL` | Defaults to `https://messenger.digitronics.app` (also sets the App Link host). |

The four Firebase values are client identifiers, not secrets, but they still
never go in Git; there is no `google-services.json` and no google-services
Gradle plugin.

### Signing

Release signing reads `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` from the environment or Gradle
properties, **only when all four are present**; otherwise `assembleRelease`
produces an unsigned APK. Never commit a keystore (`*.jks`, `*.keystore` are
ignored) or put a password in a file in this repository.

The production keystore is PKCS12 (RSA 4096, alias `digitronics-chat`; the key
shares the store password). Its only copies are two MyVault entries, the
base64 `.jks` and its password, and the `android-release` environment secrets
`ANDROID_KEYSTORE_B64` and `ANDROID_KEYSTORE_PASSWORD`. `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD` are optional and default to those. Losing it means no
installed phone can ever take an update again: Android refuses an upgrade signed
by another key, and so does the Worker's signer pin.

## Releasing

1. Tag `vMAJOR.MINOR.PATCH` (annotated; the tag message becomes the release
   notes) or run **Android release (draft upload)** by hand with a version name,
   version code and notes. A tag maps to versionCode
   `MAJOR*1000000 + MINOR*1000 + PATCH`.
2. The workflow runs in the protected `android-release` environment: it decodes
   the keystore from `ANDROID_KEYSTORE_B64`, builds and signs `assembleRelease`,
   reads package, versionCode, versionName, minSdk and the signer SHA-256 with
   `apksigner` and `aapt2`, hashes the APK, and uploads it as a **draft** to
   `POST /api/v1/mobile/platform/releases/drafts` with the
   `ANDROID_RELEASE_PUBLISHER_TOKEN` bearer.
3. A platform release reviewer opens Platform administration on the website and
   publishes the draft to a channel with a rollout percentage. Phones then see
   it through `GET /api/v1/mobile/updates/check`; the download page is
   `https://messenger.digitronics.app/install-android`.

When GitHub-hosted minutes are unavailable, run the workflow by hand with
`runner: self-hosted`. The job then goes to a registered runner labelled
`digitronics-android`, for example an `--ephemeral` runner that takes this one
job and unregisters. The secrets still reach only the job, never the person
starting it.

CI (`android-ci.yml`) runs unit tests, lint and a debug build on every pull
request and push to `main`, and never publishes.

## Developer

Developer: Dr. BADAWI Abdalsalam — https://dr-badawi-abdalsalam.com/ · Brand: DigiTronics.
