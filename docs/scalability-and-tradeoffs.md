# Scalability, failure scenarios and trade-offs

No load test was run, so this page states **design reasoning and targets, not measured
capacity**. Measured facts are labelled as such.

## 1. Expected load (assumption)

A clinic counter serves one person every few minutes; a busy organization might run 5–20
queues, with a few hundred tickets per queue per day and tens of people watching each queue.
Even 100 organizations at that size generate only a few writes per second overall. The design
targets that scale with large margins on a single small instance.

## 2. Where the bottlenecks would appear, in order

| Bottleneck | Why | First remedy |
|---|---|---|
| Per-queue row lock | writes to one queue are serialized (by design) | keep transactions short (they are: a handful of indexed statements); only an extremely hot single queue would notice |
| CPU on the free instance (0.1 CPU) | JSON, bcrypt at login, JVM | a paid instance (vertical scaling) |
| Database connections | Neon free compute and a pool of 5 | raise the pool; add PgBouncer for many instances (requires removing session-level settings) |
| SSE fan-out from one JVM | one thread writes all events | more sender threads, or several instances with a shared channel |
| Reads after every event | each subscriber refetches a snapshot | short-lived cache of the public `/live` response, or push the snapshot version so clients skip refetches |

## 3. Scaling path

1. **Vertical:** bigger instance and database compute. No code changes.
2. **Read scaling:** route `QueueReadService` reads to a read replica (they are read-only and
   snapshot-based already).
3. **Horizontal application instances:** sessions are already shared (PostgreSQL), state is in
   the database, so instances are stateless except for:
   - the **SSE registry** → add a `RealtimeGateway` that publishes through PostgreSQL
     `LISTEN/NOTIFY` or Redis Pub/Sub and lets every instance deliver to its local connections
     (ADR 0005);
   - the **login throttle** → move counters to Redis or a database table.
4. **Hot queues:** replace "MAX + 1 under the queue lock" with a counter row per queue-day
   (`INSERT ... ON CONFLICT DO UPDATE SET last = last + 1 RETURNING last`) or a PostgreSQL sequence
   per queue, and keep the queue lock only for operations that need the whole line.
5. **Partition by organization:** tenants never interact, so organizations can be sharded
   across databases with a routing table — the natural point to consider splitting services.

## 4. When microservices would be justified

Not for this load. They become reasonable when there are independent teams needing separate
release cycles, a capability with very different scaling or reliability needs (for example
SMS/WhatsApp delivery with retries and provider outages), or regulatory isolation of data.
The module boundaries (`appointment` depends on `ticket` through an interface, events between
modules, no shared entity graphs) were chosen so such a split would follow existing seams.

## 5. Failure scenarios

| Failure | What users see | Why it is safe |
|---|---|---|
| App restarts (deploy, free-tier recycling) | SSE shows "Reconnecting", then refetches; still logged in | state and sessions are in PostgreSQL; the registry completes streams before shutdown |
| App sleeps (free tier) | first request waits about a minute | nothing is lost; the client reconnects |
| Database unavailable or waking | requests fail with 5xx or wait up to 20 s for a connection; liveness stays UP | no partial writes (transactions); `readiness` reports DOWN |
| Transaction fails midway (constraint, timeout, bug) | error response | full rollback; no event, no notification, no consumed token |
| SSE event lost or duplicated | nothing visible | events are hints; every reconnect refetches the snapshot |
| Lock contention beyond 5 s | `503 QUEUE_BUSY`, retry | bounded waiting instead of hung requests |
| Staff double-click | second request gets `409` | serialized under the lock |
| Browser offline | "Reconnecting"; actions fail with a network message | reconnects on `online` |
| Clock skew between instances (future) | — | all business times come from the application clock in one instance today; with several instances use NTP-synced hosts (already true on cloud platforms) |

## 6. Trade-offs we accepted

| Choice | Gain | Price |
|---|---|---|
| Pessimistic locking over optimistic versioning | no retry loops; easy correctness argument | per-queue serialization |
| Positions computed on read | always correct, nothing to keep in sync | a query per read (cheap with the partial index) |
| Id-only events + refetch | privacy, robustness | one extra request per event per client |
| Sessions in PostgreSQL | survive restarts; stateless app | a session lookup per request |
| One counter per queue | simple invariant enforceable by an index | multi-counter services need several queues (or a future "counters" table) |
| No email/SMS | in scope and free | notifications are in-app only |

## 7. Future extensions (not implemented)

Multiple counters per queue; staff-side reassignment between queues; SMS/WhatsApp/email
notifications through an outbox table; audit log; recurring schedules and holidays per queue;
analytics (average wait by hour) from completed tickets; kiosk/TV display mode; email
verification and password reset; PWA push notifications.
