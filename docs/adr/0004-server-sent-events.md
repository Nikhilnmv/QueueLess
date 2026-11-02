# ADR 0004: Server-Sent Events for real-time browser updates

- **Status:** accepted
- **Date:** 2026-10-29

## Context

Customers must see their position and "you are called" without refreshing; staff boards must
update when customers join or leave. Updates flow from server to browser; browser actions are
ordinary REST calls. The app sits behind a hosting proxy and uses cookie sessions.

## Decision

Use SSE (`text/event-stream`) via Spring MVC `SseEmitter` and the browser's native `EventSource`:
`GET /api/queues/{id}/events` for queue screens and `GET /api/me/events` for personal
notifications. Events are published only after the database transaction commits and carry ids
only (`queueId`, `version`, `reason` / `notificationId`); clients refetch authorized data. Every
(re)connection starts with a `ready` event that triggers a snapshot refetch; heartbeats every 20 s.

## Consequences

- Same origin, same session cookie, same authorization as the REST API; nothing new to secure.
- Automatic reconnection in the browser; lost or duplicated events are harmless because the
  snapshot is the source of truth.
- No private data on broadcast channels.
- One HTTP connection per open stream (HTTP/2 multiplexes them on the hosted site; on plain
  HTTP/1.1 browsers allow about 6 per origin, so many tabs on localhost can exhaust them).
- The registry is per JVM (see ADR 0005).

## Alternatives considered

- **WebSockets (STOMP):** bidirectional, but needs its own handshake/authentication handling,
  more infrastructure and gives no benefit for one-way updates.
- **Polling:** simple, but either slow (long intervals) or wasteful (short intervals), and it keeps
  a scale-to-zero database awake.
- **Pushing full snapshots in events:** fewer requests, but leaks customer data to every
  subscriber unless snapshots are personalized, and lost events would leave stale screens.
