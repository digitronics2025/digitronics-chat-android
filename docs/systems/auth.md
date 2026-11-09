# Sign-in, tokens and sign-out

> Last verified: 2026-11-09 — first version. Server half: whatsapp-inbox-saas `docs/systems/mobile-auth.md`.

## Flow
1. **Start** (`SessionManager.beginSignIn`): 32 random bytes → base64url
   verifier (43 chars), S256 challenge, 32-byte random `state`. Verifier and
   state are sealed with the Keystore cipher and stored with a start time.
2. **Consent**: a Custom Tab opens
   `${BASE}/android-auth/authorize?client_id=digitronics-chat-android&redirect_uri=${BASE}/android-auth/callback&code_challenge=…&code_challenge_method=S256&state=…`.
3. **Hand-back**: `MainActivity` has one intent filter, `https://<host>/android-auth/callback`
   with `autoVerify="true"`. The server opens it with a package-pinned
   `intent://…;scheme=https;package=com.digitronics.chat;end`, which this same
   filter receives. The intent is consumed once and replaced, so rotation
   cannot replay the code.
4. **Exchange** (`completeSignIn`): the pending entry is taken (one use, 10-minute
   limit), `state` compared in constant time, then
   `POST /api/v1/mobile/auth/token` (`authorization_code`) with the verifier,
   the redirect URI and the device registration (random per-install UUID,
   `Build.MODEL`, version code/name, SDK, `stable`).
5. `GET /api/v1/mobile/session` names the person and workspace. The account key
   `userId@workspaceId` namespaces every cached row.

## Tokens
- Access token: memory only (`SecureTokenStore`), treated as expired 60 s early.
- Refresh token: AES-256-GCM under a non-exportable Android Keystore key
  (`KeystoreCipher`, no security-crypto library), in the `credentials`
  DataStore. Backups are off (`allowBackup=false`, extraction rules exclude
  everything).
- `BearerInterceptor` adds `Authorization: Bearer` for our host only and
  refreshes first when there is no live access token. `AppVersionInterceptor`
  adds `X-App-Version-Code` to every request.
- `RefreshAuthenticator` handles a 401 once. All refreshes go through
  `TokenRefresher`, which holds one Mutex: a caller whose failed token was
  already replaced gets the new one without a network call. This matters
  because a reused refresh token makes the server revoke the device.
- `ApiClient` runs every request **synchronously on Dispatchers.IO**, and the
  token endpoint has its own OkHttp `Dispatcher`. With async `enqueue`, five
  concurrent 401s filled OkHttp's 5-per-host limit while blocked in the
  authenticator, and the refresh queued behind them forever (caught by
  `AuthenticatorIntegrationTest`, 12 parallel calls on one shared client).
- `invalid_grant` (or any non-transient 4xx from the token endpoint) ends the
  session; network errors and 5xx surface as IOExceptions and keep it.

## Ending a session
`AppContainer.wipe` (NonCancellable): stop realtime, delete the FCM token (5 s
budget), clear tokens, clear every Room table, clear account preferences,
cancel notifications, reset update state, cancel all WorkManager jobs. Runs on
Sign out (after `POST /api/v1/mobile/logout`, best effort), on a refused
refresh, and when the session endpoint reports a different person.

## Known limits
- Debug builds (`.debug` package) do not receive the server's package-pinned
  hand-back; sign-in on a debug build needs the debug signer in the server's
  `ANDROID_DEBUG_APP_LINK_SHA256S` and a verified App Link, or a release build.
- A fully offline revoked phone keeps its cache until its next server contact.
- Not tested on a device or emulator.
