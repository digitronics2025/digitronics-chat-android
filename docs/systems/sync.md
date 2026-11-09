# Chat sync, cache and realtime

> Last verified: 2026-11-09 — first version. Server: `worker/src/routes/direct-messages.ts`, `worker/src/durable-objects/dm-realtime.ts`.

## Cache
Room (`chat-cache.db`) is a bounded cache, never the source of truth. Tables:
`conversations`, `messages`, `thread_cursors`, `outgoing`; every primary key
starts with the account key. Bounds: 300 conversations; a thread over 600
cached messages is dropped (and reloaded on next open) unless it is on screen.
A schema change drops and refetches (`fallbackToDestructiveMigration`).

## Conversations
`GET /api/v1/dm/conversations?limit=50&cursor=…`, drained up to 300 rows. When
the whole list was fetched, rows the server no longer lists (and their
messages) are deleted. Filters are local: Unread (`unreadCount > 0`), Groups
(`group != null`), Bots (`peer.isBot`).

## Messages
- **First open**: `GET …/messages?direction=before&limit=50` (newest first).
  Store `headCursor` (the newest message, `s:<sequence>`) and `nextCursor`
  (to page back).
- **Delta**: `direction=after&cursor=<headCursor>&limit=100`, repeated while a
  page is full and `nextCursor` is set (max 20 pages); `headCursor` advances to
  the response's value when present.
- **Older**: `direction=before&cursor=<olderCursor>` near the top of the list.
- Merge by server id; a server message carrying a `clientMessageId` removes
  the matching outgoing row. One Mutex per thread serialises syncs.
- Delta by sequence does not see edits, deletions or reactions to older
  messages; reopening after a cache drop does.

## Sending
`ChatRepository.send` writes an `outgoing` row with a fresh UUID
`clientMessageId` and enqueues `SendMessageWorker` (unique per id, network
constraint, exponential backoff from 10 s, up to 6 attempts). Each attempt
posts the same `clientMessageId`, so the server returns the stored message for
a duplicate. Transient (408/429/5xx/network) retries; other errors mark the
row failed with Retry / Discard in the bubble. A worker only sends for the
account that queued it.

## Read state
Opening a thread (and each new newest message) posts
`POST …/read {messageId}` for the newest cached message and zeroes the local
unread count. Ticks on my 1:1 messages: ✓✓ when `readAt` is set or the message
is at or before the peer's `peerLastReadMessageId`; groups show no ticks.

## Realtime
`RealtimeClient` connects to `wss://<host>/api/v1/dm/realtime/connect` only
while the app is in the foreground, with the `Authorization: Bearer` header
(never a query token). It sends `{"type":"ping"}` every 25 s; 70 s without a
frame drops the socket. Events: `dm.message.created` → delta for the thread on
screen + list refresh (debounced 300 ms); `dm.message.read` → peer watermark;
`dm.group.deleted` → list refresh. Close 4001/4003 or a 401/403 handshake →
refresh tokens, then reconnect; a refused refresh signs out. Other failures
back off 0.5–60 s with jitter. Every (re)connect re-syncs by cursor.

## Not tested
No instrumentation or device tests yet; the Room/sync code is exercised only by
compilation and lint.
