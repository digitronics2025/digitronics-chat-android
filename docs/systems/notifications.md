# Notifications (FCM)

> Last verified: 2026-11-09 — first version. Server: whatsapp-inbox-saas `docs/systems/native-push.md`.

## Firebase without google-services
`PushConfig.ensureInitialized` builds `FirebaseOptions` from BuildConfig
(`FIREBASE_PROJECT_ID`, `FIREBASE_APP_ID`, `FIREBASE_API_KEY`,
`FIREBASE_SENDER_ID`). Any empty → push off, Settings shows "Notifications not
available in this build". The manifest sets
`firebase_messaging_auto_init_enabled=false`; auto-init is switched on only
after manual initialisation succeeds. No Analytics.

## Registration
`PushRegistrationWorker` → `PushRegistrar.sync`: reads the FCM token and
whether notifications are allowed, and `PUT /api/v1/mobile/devices/current/push`
`{ token, notificationsEnabled }` only when that pair changed (a SHA-256
fingerprint is kept, never the token). Runs after sign-in (forced), on
`onNewToken`, on each foreground, and after the permission is granted.

## Receiving (`ChatMessagingService`)
- `type=dm.message` (`conversationId`, `messageId`, `title`, `system`): if the
  thread is on screen, just sync. Otherwise sync the thread and list (15 s
  budget), skip my own messages, and post on **Messages** or **Bot reports**:
  title = group title or sender, text = body (or the bot's title). Tag
  `conversation:<id>` (newer replaces older), de-duplicated by `messageId`
  (last 200 remembered), lock-screen `VISIBILITY_PRIVATE` with a generic public
  version ("New message"). Too slow → a generic notification without content.
  Tap opens the thread only once a live session exists.
- `type=app_release_available`: only enqueues an update check. If a verified
  newer version appears, one **App updates** notification per versionCode.
- `type=test`: a test notification.
- Signed out: nothing is shown.

## Permission
Android 13+: the chat list shows an explanation card with **Allow** / **Not
now**; Settings has **Turn on** and **Open system settings**. Never at launch.

## Test
Settings → **Send test notification** posts
`/api/v1/mobile/devices/current/push/test` and reports `accepted` as "Google
accepted it … this does not prove the phone displayed it".

## Not tested
No Firebase project exists, so no token has been registered and no push has
been received by this app.
