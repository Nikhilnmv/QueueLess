# ADR 0002: PostgreSQL as the single source of truth

- **Status:** accepted
- **Date:** 2026-10-29

## Context

Bookings and queue positions must stay correct when many people act at once: no slot may be
overbooked, no token issued twice, no ticket called twice. Data must survive restarts of a free
host that may sleep or restart at any time. A free managed offering is required.

## Decision

Store all state (including HTTP sessions) in PostgreSQL, hosted on Neon. Use:

- transactions with row-level locks (`SELECT ... FOR UPDATE`) to serialize conflicting writes;
- declarative constraints as the last line of defence: unique constraints, **partial unique
  indexes** (one ticket at the counter per queue and day; one active ticket/booking per customer),
  **CHECK constraints** tying lifecycle timestamps to status, a **composite foreign key** keeping
  an appointment's ticket in the same queue, and a **generated column** for index-supported
  priority ordering;
- Flyway migrations for every schema change; Hibernate only validates.

## Consequences

- Invariants hold even if application code has a bug (proved by tests that insert invalid rows
  with raw SQL).
- Integration tests must run on real PostgreSQL (H2 lacks these semantics); a local cluster script
  and Docker Compose are provided.
- Positions are derived by queries rather than stored, so they cannot drift.
- Neon's free tier sleeps after 5 minutes idle; the app is configured not to keep it awake.

## Alternatives considered

- **MySQL:** row locks are available, but there are no partial indexes or generated-column
  indexing equivalent in the same form, and the free hosted options are weaker.
- **MongoDB / document store:** multi-document invariants need transactions and lose declarative
  constraints.
- **In-memory state with periodic persistence:** loses data on restart and cannot coordinate
  several instances.
- **H2 for tests:** different locking and index behaviour would make the concurrency tests
  meaningless.
