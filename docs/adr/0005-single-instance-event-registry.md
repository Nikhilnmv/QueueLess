# ADR 0005: Single-instance SSE registry behind a transport abstraction

- **Status:** accepted
- **Date:** 2026-10-29

## Context

Open SSE connections live in the JVM that accepted them. The free host runs exactly one
instance. A multi-instance deployment would need every instance to learn about changes committed
by the others.

## Decision

Keep an in-memory, thread-safe `SseEmitterRegistry` for now, but put a seam in front of it:
business services publish Spring application events; `RealtimeEventRelay` forwards them after
commit to the `RealtimeGateway` interface; `SseRealtimeGateway` is the single-instance
implementation. Do **not** add Redis or a broker now.

## Consequences

- Simple and dependency-free; correct for one instance.
- Documented limitation: with several instances, browsers connected to instance B would miss
  pushes for changes committed on instance A (they would still be correct after their next
  refetch or reconnect).
- Evolution path without touching business code: implement a `RealtimeGateway` that publishes to
  PostgreSQL `LISTEN/NOTIFY` (no new infrastructure; one dedicated connection per instance,
  payloads are tiny ids) or Redis Pub/Sub, and have each instance subscribe and deliver to its local
  registry. Sessions are already shared through PostgreSQL, so no sticky sessions are required.
- The in-memory login throttle has the same single-instance limitation and the same remedy
  (shared store).

## Alternatives considered

- **Redis Pub/Sub now:** adds a service (and a paid or extra free account) to solve a problem the
  deployment does not have.
- **PostgreSQL LISTEN/NOTIFY now:** feasible, but needs a dedicated non-pooled connection and
  reconnection handling — complexity without benefit at one instance.
- **Sticky sessions per queue:** fragile and still loses events on failover.
