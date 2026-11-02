# Viva preparation

Short, accurate answers to the questions examiners are most likely to ask. Each answer points
to the code or document that backs it up, so you can show it on screen.

## Overview

**1. What does QueueLess do?**
It lets clinics, college offices, labs and help desks run service queues online. Customers book
appointment slots or take a walk-in token, follow their live position and estimated wait, and are
notified when their turn approaches and when they are called. Staff call, serve, complete or mark
no-shows; administrators configure queues, schedules, capacity and staff. Many organizations
share the platform, isolated from each other.

**2. Describe the architecture in one minute.**
A React single-page app talks to one Spring Boot application over HTTPS (REST) and receives live
updates over Server-Sent Events. The backend is a modular monolith with modules for auth,
organizations, memberships, queues, appointments, tickets, notifications and real-time delivery.
PostgreSQL (Neon) is the single source of truth, schema managed by Flyway. In production one
Docker container on Render serves both the React build and the API from one origin.
([architecture.md](architecture.md))

**3. Why a monolith and not microservices?**
Booking an appointment, reserving its token and creating a notification must commit together;
in one process that is a local transaction. One student, two weeks and one free 512 MB instance
make microservices a cost with no benefit at this load. Modules have clean boundaries (two
interfaces break dependency cycles), so services could be extracted later when there are separate
teams or very different scaling needs. ([ADR 0001](adr/0001-modular-monolith.md))

## Concurrency (the core of the design)

**4. How do you guarantee unique token numbers?**
Every allocation runs in a transaction that first locks the queue's row with
`SELECT ... FOR UPDATE`. While the lock is held, `MAX(token_number) + 1` for that queue and day is
computed and the ticket inserted; commit releases the lock. The next transaction waits, then (at
READ COMMITTED) sees the committed ticket, so its `MAX` is higher. A unique constraint on
`(queue_id, service_date, token_number)` is the final safeguard. The test fires 30 simultaneous
walk-ins and gets exactly tokens 1–30. ([TokenAllocator](../backend/src/main/java/com/queueless/ticket/service/TokenAllocator.java))

**5. Two people try to book the last place in a slot at the same moment. What happens?**
Both requests try to lock the same queue row; one waits. The first counts the non-cancelled
bookings for the slot, finds room, inserts and commits. The second then counts again, sees the
slot full and gets `409 SLOT_FULL`. Tested with 10 customers competing for 1 place: exactly one
succeeds.

**6. Why not just use `synchronized` in Java?**
It only protects threads inside one JVM. A second application instance, or any other process
writing to the database, would bypass it. The database row lock works across all of them.

**7. Why pessimistic locking rather than optimistic (`@Version`)?**
Optimistic locking detects conflicts at commit and forces a retry; on a busy queue many requests
would retry. A short pessimistic lock makes requests simply wait their turn, the code has no retry
loops, and correctness is easy to argue. The cost is that writes to one queue are serialized,
which is fine because each takes only a few milliseconds. ([ADR 0003](adr/0003-transactional-locking-for-tokens.md))

**8. Can deadlocks happen?**
Not between business transactions: each locks exactly one queue row (membership changes lock one
organization row and never a queue), so there is no circular wait. Lock waits are capped at 5
seconds (`lock_timeout`), after which the user gets `503 QUEUE_BUSY` instead of a hung request.

**9. Two staff members press "Call next" together. Can one customer be called twice, or two at
once?**
No. Calls are serialized by the queue lock; the second sees a ticket already at the counter and
gets `409 ACTIVE_TICKET_EXISTS`. Independently, a partial unique index allows at most one ticket
with status CALLED or SERVING per queue and day, so even buggy code could not store two. Tested
over 10 rounds of 8 simultaneous calls.

**10. What isolation level do you use?**
PostgreSQL's default READ COMMITTED for writes, combined with explicit row locks — sufficient
because all conflicting writers share one lock. Reads that assemble a screen from several queries
run read-only at REPEATABLE READ, so they see one consistent snapshot (a ticket can never appear
both waiting and called).

**11. A user double-clicks "Confirm booking". Do they get two bookings?**
No, for three reasons: the button is disabled while the request runs; the client sends an
`Idempotency-Key` (a UUID per chosen slot) and the server returns the existing booking when it
sees the key again (unique constraint on user + key); and a customer may hold only one active
booking per queue per day (partial unique index). Tested with 10 simultaneous identical
submissions → one row.

**12. Why publish real-time events only after commit?**
If we pushed inside the transaction and it then rolled back, browsers would show a change that
never happened. `@TransactionalEventListener(AFTER_COMMIT)` runs only for committed transactions;
a test forces a rollback and asserts that nothing was sent.

## Queue logic

**13. How is the queue ordered? Is it fair?**
Staff-set priority first (High, Normal, Low), then check-in time, then creation time and id as
tie-breakers. Customers cannot raise their own priority. Appointment holders join the line when
they check in (from 60 minutes before their slot), so people already present are not overtaken by
someone who merely booked earlier. The policy is shown on every staff board.

**14. How do you compute a customer's position? Why not store it?**
Position is the index of the ticket in the ordered list of waiting tickets, plus one. It is
computed at read time from the database. A stored number would become wrong whenever someone
ahead leaves, is called or is re-prioritized.

**15. How is the waiting time estimated?**
Estimate = time left for the ticket at the counter + people ahead × average service time. The
average is the mean of the last 20 completed services, each clamped to between 1 minute and 3×
the configured default; with fewer than 3 samples the configured default is used. For a ticket
being served, remaining = average − elapsed (never negative); for one just called, a full average.
It is an estimate: priorities and no-shows change it, and the UI says so.
([queue-algorithms.md](queue-algorithms.md))

**16. Explain the ticket lifecycle.**
BOOKED (appointment reserved) → WAITING (check-in or walk-in) → CALLED → SERVING → COMPLETED;
CALLED can become NO_SHOW; BOOKED or WAITING can be CANCELLED. Terminal states never change, so a
cancelled ticket cannot re-enter. Enforced in `TicketStatus`/`QueueTicket` and by CHECK
constraints that require the matching timestamps for each status.

**17. How do appointments and tickets stay consistent?**
Booking creates both in one transaction. Every ticket transition notifies
`AppointmentTicketSync` (through an interface owned by the ticket module) in the same transaction,
which moves the appointment to CHECKED_IN, COMPLETED, NO_SHOW or CANCELLED. A unique constraint
and a composite foreign key ensure one ticket per appointment, in the same queue.

## Real time

**18. Why SSE instead of WebSockets or polling?**
Updates only flow from server to browser. SSE is plain HTTP with the same cookie and origin as the
API, works through proxies and reconnects automatically. WebSockets add a second protocol for a
two-way channel we do not need; polling is either slow or wasteful and keeps a sleeping database
awake. ([ADR 0004](adr/0004-server-sent-events.md))

**19. What if an event is lost (network drop, server restart)?**
Nothing breaks. Events are only hints ("queue X changed, version N"). On every reconnect the
server sends `ready` and the client re-fetches the current snapshot from the database, which is
always authoritative.

**20. Does broadcasting queue events leak data?**
No. Events contain only the queue id, a version number and a reason. Private details (names on the
staff board, your own ticket) are fetched through authorized REST calls.

**21. Does it work with several server instances?**
Not yet for live push: the connection registry is per JVM. Sessions and data are already shared in
PostgreSQL. The fix is a `RealtimeGateway` that publishes through PostgreSQL LISTEN/NOTIFY or Redis
Pub/Sub so every instance can deliver to its own connections; business code would not change.
([ADR 0005](adr/0005-single-instance-event-registry.md))

## Security and multi-tenancy

**22. How do you stop an admin of organization B from editing organization A's queue by changing
the id in the URL?**
The service never trusts the id's organization. It loads the queue's organization from the
database and checks that the caller has the required role in *that* organization
(`AccessControlService.require`). Admin B has no membership in A, so the request gets 403.
`TenantIsolationIT` tries exactly these attacks.

**23. Why do some requests return 404 instead of 403?**
For private resources (another customer's ticket or appointment), 404 avoids confirming that the
id exists. For public resources (a queue), 403 is honest because the queue is visible anyway.

**24. How is authentication done? Why not JWT?**
Email and password, checked by Spring Security; a successful login creates a server-side session
stored in PostgreSQL, referenced by an HTTP-only, SameSite=Lax, Secure cookie; the session id is
rotated at login. Nothing is stored in localStorage, logout is real, and sessions survive restarts.
JWTs in localStorage are exposed to XSS and hard to revoke. ([ADR 0006](adr/0006-sessions-in-postgresql.md))

**25. What is CSRF and how do you prevent it?**
A malicious site could make the browser send a request with our cookie. Every state-changing
request must carry an `X-XSRF-TOKEN` header equal to the `XSRF-TOKEN` cookie; another site can make
the browser send the cookie but cannot read it to set the header. SameSite=Lax adds a second
barrier.

**26. How are passwords stored?**
As bcrypt hashes from Spring Security's delegating encoder (with an `{bcrypt}` prefix so the
algorithm can be upgraded). Never in plain text, never returned, never logged. Login failures are
throttled per email and per IP.

**27. Can a customer make themselves staff?**
No. Registration ignores role fields; roles are memberships created only by an organization admin
(or the platform operator for the first admin). Priority changes require STAFF. Tests check both.

## Database

**28. Walk me through the schema.**
users, organizations, organization_memberships (user + org + role, unique per pair),
service_queues (configuration, status, version), appointments (slot, reference, status,
idempotency key), queue_tickets (token, status, priority, lifecycle timestamps, optional
appointment, optional user for guests), notifications. UUID keys, timestamptz instants, CHECK
constraints for enums and state rules. ([database-design.md](database-design.md))

**29. Justify one index.**
`ix_tickets_waiting_order (queue_id, service_date, priority_rank, checked_in_at, created_at, id)
WHERE status = 'WAITING'` matches the call-next query exactly: filter by queue, day and status,
order by the service order, take one row. It is partial, so it only contains waiting tickets and
stays small. `priority_rank` is a generated column because ordering by a CASE expression could not
use an ordinary index.

**30. What is a partial unique index and where do you use it?**
A unique index over only the rows matching a condition. `UNIQUE (queue_id, service_date) WHERE
status IN ('CALLED','SERVING')` means "at most one ticket at the counter per queue and day" while
allowing any number of completed or waiting tickets.

**31. How are time zones handled?**
Instants are timestamptz (UTC). Opening hours and service dates are local `time`/`date` values
interpreted in the organization's IANA time zone. We found a real bug: a Hibernate setting
(`jdbc.time_zone=UTC`) shifted local times by the JVM offset, invisible in round trips; a smoke
test exposed it, we removed the setting and added a regression test that runs in a +05:30 JVM.

**32. Why Flyway?**
Version-controlled, repeatable schema changes applied automatically at startup; checksums stop
edited migrations; Hibernate only validates. Tests rebuild the schema from scratch every run.

## Testing and deployment

**33. How did you test concurrency?**
Against real PostgreSQL. Each test parks N threads on a `CountDownLatch` and releases them at once
so the requests really overlap, then checks invariants in the database: tokens 1..N, exactly
capacity bookings, one call per round, one row per idempotency key, one event per successful
operation. ([testing.md](testing.md))

**34. Why not H2 for tests?**
H2 has different locking, no partial indexes or the same generated-column semantics; a passing H2
test would not prove the PostgreSQL guarantees we rely on.

**35. How is it deployed, and what are the free-tier limits?**
GitHub → Render free Docker web service (0.1 CPU, 512 MB, sleeps after 15 minutes idle, about a
minute to wake) → Neon free PostgreSQL (scale-to-zero after 5 minutes). The JVM is tuned to about
320 MB, a CDS archive speeds up startup, the health check never touches the database, and idle
connections are closed so Neon can sleep. ([deployment.md](deployment.md))

**36. How many users can it handle?**
We did not run a load test, so we do not claim a number. Reasoning: writes per queue are serialized
but each takes a few milliseconds, far above a physical counter's pace; different queues run in
parallel; reads are indexed. The first limits would be the 0.1-CPU free instance and the single SSE
sender thread. ([scalability-and-tradeoffs.md](scalability-and-tradeoffs.md))

**37. What happens if the database is down?**
Requests fail cleanly with errors and no partial writes (everything is transactional); liveness stays
UP so the host does not restart-loop, readiness reports DOWN; when the database returns, everything
continues with no repair needed.

**38. What would you do next?**
Multi-instance event fan-out, shared rate limiting, multiple counters per queue, an outbox for
SMS/email notifications, an audit log, load testing, email verification and password reset.

## Demo script (5 minutes)

1. Landing → Find a service → City Care Clinic: live waiting counts per queue.
2. Log in as `asha@example.com`, General OPD → **Get a token** → ticket page (position, estimate, Live).
3. Private window: `staff@citycare.example` → Staff → General OPD board; change Asha to **High**
   priority (she jumps to #1), **Call next** → Asha's window shows "It's your turn!" and a toast.
4. Start → Complete; show the stats tile update.
5. As Asha: book tomorrow's slot, show the reference and token; cancel it and show the slot freed.
6. Log in as `staff@northbridge.example` and open the City Care board URL → access denied.
7. Admin (`admin@citycare.example`): create a queue, add a member, try to demote the last admin.
8. Show `ConcurrencyIT` and its passing run, and the partial unique index in V2 migration.
