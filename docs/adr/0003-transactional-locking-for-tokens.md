# ADR 0003: Pessimistic queue-row lock for token allocation and queue operations

- **Status:** accepted
- **Date:** 2026-10-29

## Context

Token numbers must be unique and sequential per queue and service day. A naive
`SELECT MAX(token_number) + 1` lets two concurrent requests read the same maximum. The same
"read, decide, write" race exists for slot capacity, call-next and cancellation.

## Decision

Every write to a queue's tickets or appointments begins by locking that queue's row:
`QueueLockService.lock(queueId)` performs `EntityManager.refresh(queue, PESSIMISTIC_WRITE)`
(`SELECT ... FOR UPDATE` plus a reload) in the caller's transaction. Inside the lock the service
re-reads state, validates and writes; `MAX + 1` is then safe. `UNIQUE (queue_id, service_date,
token_number)` remains as the final safeguard. Methods that require the lock take a
`LockedQueue` parameter that only the lock service can create. Lock waits are bounded
(`lock_timeout = 5s` → `503 QUEUE_BUSY`).

## Consequences

- One simple rule covers tokens, capacity, call-next, check-in, cancellation and priority changes.
- Deadlocks between business transactions are impossible (one lock per transaction).
- Writes to one queue are serialized; different queues run in parallel. Each transaction is a few
  indexed statements, so a single queue can sustain far more operations than a human counter needs
  — but this is a deliberate throughput ceiling per queue, not global scalability.
- Tested with genuinely simultaneous requests (`ConcurrencyIT`).

## Alternatives considered

- **Optimistic locking** (`@Version` + retry): correct, but every conflict becomes a retry loop,
  and busy queues would retry often; harder to explain.
- **SERIALIZABLE isolation:** PostgreSQL would detect conflicts, but the application must retry
  serialization failures everywhere.
- **Counter table / sequence per queue-day:** better for very hot queues (no queue-wide lock for
  issuing tokens) — kept as the documented next step; capacity and call-next would still need
  coordination.
- **`synchronized` / Java locks:** protect one JVM only; useless with several instances and do not
  protect the database.
- **Advisory locks:** would work, but row locks are clearer, released automatically at commit, and
  compatible with a future connection pooler.
