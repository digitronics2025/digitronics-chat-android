# Project safeguards

Project-specific safety and product constraints below remain binding. Current
authority, execution, verification classification and release procedures are in
[the authored contract](agent-contract.md), which resolves any old process wording.
Past approvals are not permission; verify dated claims against current code.
Read relevant sections before changing their subsystem.

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

