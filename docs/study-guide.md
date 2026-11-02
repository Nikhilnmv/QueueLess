# Study guide for the viva

This guide teaches the whole project in the order you would explain it to an examiner.

**How to use it:**
1. Read sections 1 to 9 with the code open; this takes about two evenings.
2. Use section 10 to explain the diagrams.
3. Practise the questions in section 12 aloud.

Every section names the files to open, so you can show the proof on screen. To run the app and
try every role, follow the [user guide](user-guide.md) first.

## 1. The project in two minutes

**Say this first.** QueueLess is a web application that lets clinics, college offices and service
counters run their queues online.
- A visitor takes a token for today from their phone, or books an appointment slot.
- They then watch their place in line and estimated wait update live.
- They are notified when their turn is near and when they are called.
- Staff run the counter from a live board.
- Administrators set up queues, opening hours, slot sizes and staff.
- One installation serves many organizations, and each sees only its own data.

The backend is Spring Boot with PostgreSQL, and the frontend is React. It runs on free hosting
(Render for the app, Neon for the database).

**The problem.** In a physical queue people stand and wait without knowing how long it will take.
Paper tokens and display boards only work when you are in the room, and commercial systems cost
money and need hardware. QueueLess needs only a browser: no app to install and no special
hardware.

**Objectives.**
1. Let visitors join a queue or book a slot remotely and see their live position and wait.
2. Give staff one screen to call, serve and manage the line.
3. Keep the data correct when many people act at the same moment: no duplicate tokens, no
   overbooked slots, never two people called at once.
4. Keep organizations isolated from each other.
5. Deploy it at no cost.

**Out of scope.** SMS or email messages, payments, several counters inside one queue, and native
mobile apps. Section 11 lists these as future work.

## 2. The building blocks

| Part | Technology | Why this one |
|---|---|---|
| Backend | Java 21, Spring Boot 3.5 | mature, well documented; transactions, security and validation built in |
| Database | PostgreSQL 17 | real row locks, partial unique indexes, generated columns, CHECK constraints: the guarantees the design relies on |
| Schema changes | Flyway | numbered SQL migrations applied at startup; Hibernate only validates |
| Login | Spring Security, Spring Session JDBC | server-side sessions stored in PostgreSQL, HTTP-only cookie, CSRF protection |
| Frontend | React 19, TypeScript, Vite | typed components, fast build |
| Server data in the browser | TanStack Query | caches each screen's data and re-fetches it when it is out of date |
| Styling | Tailwind CSS, IBM Plex fonts | small hand-made component set, no UI library |
| Live updates | Server-Sent Events (SSE) | one-way push over plain HTTP with the same cookie |
| Tests | JUnit 5, Spring Boot Test, Playwright | unit, integration on real PostgreSQL, and browser tests |
| Hosting | Render (Docker), Neon (PostgreSQL) | free plans; one container serves everything |

**Repository map.**

```
backend/src/main/java/com/queueless/
  auth/           registration, login, sessions, CSRF (SecurityConfig, AuthService, AttemptLimiter)
  organization/   organizations and public discovery
  membership/     roles, permissions, AccessControlService, admin and platform endpoints
  queue/          ServiceQueue, QueueLockService + LockedQueue, queue settings, the SSE endpoint
  appointment/    slot grid (SlotSchedule, BookingRules), booking (AppointmentService)
  ticket/         tickets, TokenAllocator, TicketService, QueueOperationsService,
                  QueueReadService, WaitTimeEstimator, QueueChangeRecorder
  notification/   stored notifications (NotificationService)
  realtime/       SSE connections (SseEmitterRegistry) and after-commit delivery (RealtimeEventRelay)
  common/         error responses, TimeProvider, database URL conversion, SPA routing
  seed/           demo data
backend/src/main/resources/db/migration/   V1 to V4 SQL migrations
frontend/src/
  api/            client.ts (fetch + CSRF + errors), queries.ts (TanStack Query hooks), types.ts
  auth/           route guard (RequireAuth), role helpers
  realtime/       EventSource hooks that refresh data on live events
  components/     Layout, ui.tsx (buttons, panels, tables, dialogs), StatusBadges, Toast
  pages/          one file per screen; staff/, admin/, platform/
  lib/format.ts   dates and times in the organization's time zone
frontend/e2e/     Playwright browser tests
docs/             design documents, ADRs, diagrams, these guides
scripts/          run-local.sh, local-postgres.sh, smoke-test.sh, render-diagrams.mjs
```

**Layers inside a backend module.**
1. `api` holds controllers and request and response records (DTOs). Controllers only translate
   HTTP to method calls.
2. `service` holds transactions, rules and permission checks.
3. `domain` holds JPA entities, repositories and enums.

Controllers never return entities, so the API cannot leak a field by accident.

**Architecture style.** QueueLess is a *modular monolith*: one deployable application, split into
modules with clear boundaries ([ADR 0001](adr/0001-modular-monolith.md)). The browser talks to
one origin, and the same server serves the React files, the REST API and the SSE streams. See
diagram [01](diagrams/README.md#high-level-system-architecture).

## 3. Follow one request: "Get a token"

If you can tell this story with the files open, you understand the backbone of the project.

1. **Button.** `pages/QueuePage.tsx` calls the `useJoinQueue` mutation in `api/queries.ts`. That
   sends `POST /api/queues/{queueId}/tickets` through `api/client.ts`, which adds the
   `X-XSRF-TOKEN` header copied from the `XSRF-TOKEN` cookie.
2. **Security filters.** Spring Security finds the session from the HTTP-only cookie (sessions
   live in PostgreSQL) and checks that the CSRF header matches. Without a session the answer is
   `401`.
3. **Controller.** `ticket/api/TicketController` takes the user id from the authenticated
   principal, never from the request body, and calls `TicketService.joinQueue(queueId, userId)`.
4. **Transaction and lock.** `joinQueue` runs in one database transaction. Its first action is
   `QueueLockService.lock(queueId)`, which runs `SELECT … FOR UPDATE` on the queue's row and
   returns a `LockedQueue`. Another request for the same queue now waits here until this one
   commits.
5. **Rules, checked under the lock:**
   - The organization must be active.
   - The queue must accept arrivals (OPEN or PAUSED).
   - "Today" is worked out in the organization's time zone from `TimeProvider`.
   - The user must not already hold an active ticket in this queue today. If they do, the answer
     is `409 ALREADY_IN_QUEUE` with the existing ticket id, which the page links to.
6. **Token number.** `TokenAllocator.nextToken(locked, today)` computes `MAX(token_number) + 1` for
   this queue and day. It needs the `LockedQueue` argument, so calling it without the lock does
   not even compile.
7. **Insert.** The new `QueueTicket` is saved as WAITING, with its check-in time set to now. The
   user also gets a CUSTOMER membership of the organization (an insert that does nothing if it
   already exists).
8. **Record the change.** `QueueChangeRecorder.record(...)` does three things:
   - increments the queue's `state_version`
   - sends a one-time "your turn is near" notification to the first three people waiting
   - publishes a `QueueChangedEvent` inside the transaction
9. **Commit.** The commit makes everything visible at once and releases the lock.
10. **After commit only.** `RealtimeEventRelay` (a `@TransactionalEventListener(AFTER_COMMIT)`)
    passes the event to `SseEmitterRegistry`. That sends `queue-changed {queueId, version,
    reason}` to every browser watching this queue. If the transaction had rolled back, nothing
    would be sent.
11. **Response.** The controller returns the new ticket as a DTO. The page goes to
    `/tickets/{id}`, where `TicketPage.tsx` shows the token and position. It also opens the queue's
    event stream (`realtime/hooks.ts`).
12. **Everyone else.** The staff board and other visitors' pages get the `queue-changed` event,
    mark their cached data as stale, and re-fetch it through normal authorized REST calls. That
    is how positions move without a reload.

Diagrams [04](diagrams/README.md#concurrent-token-generation) and
[06](diagrams/README.md#live-queue-update-sse) show steps 4 to 12 as sequence diagrams.

## 4. The data model

Tables (migrations `V1` to `V4`; [database-design.md](database-design.md), diagrams 02 and 12):

| Table | Holds |
|---|---|
| `users` | email (stored lower-case), name, bcrypt password hash, platform-admin flag |
| `organizations` | name, unique slug for the web address, IANA time zone |
| `organization_memberships` | user + organization + role (CUSTOMER, STAFF, ADMIN); one row per pair |
| `service_queues` | a counter: token prefix, status, hours, slot length, capacity, typical service time, `state_version` |
| `appointments` | booked slot, booking reference, status, idempotency key |
| `queue_tickets` | token number, status, priority, lifecycle timestamps, optional appointment, optional user (empty for guests) |
| `notifications` | stored messages for a user, read or unread |
| `spring_session*` | login sessions |

**Constraints that carry the rules.** Learn these names; examiners like seeing a rule enforced by
the database itself.

| Constraint | Rule |
|---|---|
| `uq_tickets_token` unique (queue, date, token number) | no duplicate token in a queue on a day |
| `ux_tickets_active_per_queue_day`, partial unique (queue, date) where status is CALLED or SERVING | at most one person at the counter |
| `ux_tickets_active_per_user_day`, partial unique (queue, user, date) for active tickets | one active ticket per visitor, queue and day |
| `ux_appointments_active_per_user_day`, partial unique for BOOKED or CHECKED_IN | one active booking per visitor, queue and day |
| `uq_appointments_idempotency` unique (user, idempotency key) | a repeated booking request cannot create a second booking |
| `uq_tickets_appointment` plus a composite foreign key | each appointment has exactly one ticket, in the same queue |
| CHECK constraints | allowed statuses, and the timestamps each status requires (a SERVING ticket must have `service_started_at`) |
| `priority_rank` generated column, partial index `ix_tickets_waiting_order` | the waiting-line order can be read straight from an index |

**Time.** Moments are stored as `timestamptz` (an instant in UTC). Opening hours and service dates
are local `time` and `date` values, read in the organization's time zone.

One real bug is worth telling. A Hibernate setting (`hibernate.jdbc.time_zone=UTC`) silently
shifted local times by the server's offset, so 09:00 was stored as 03:30. The smoke test found it.
The setting was removed, and `TimeZoneStorageIT` runs in an India-time JVM so the bug cannot
return.

## 5. Concurrency: the heart of the design

**The problem.** Many people act on the same queue at the same moment:
- two walk-ins at once
- two bookings for the last place in a slot
- two staff pressing Call next together
- a visitor cancelling while being called

Without care, two people get the same token number, or a slot is overbooked.

**The solution, in four parts** ([concurrency-and-transactions.md](concurrency-and-transactions.md)):
1. **One lock per queue.** Every write that touches a queue's tickets or appointments starts by
   locking that queue's row. Writes to the same queue happen one after another; different queues
   still run in parallel. Each write re-reads the current state under the lock, validates it, then
   writes.
2. **The database as a safety net.** The constraints in section 4 reject impossible states even if
   the code had a bug.
3. **Events after commit.** Browsers hear only about changes that were really saved.
4. **Consistent reads.** Screens are assembled in read-only REPEATABLE READ transactions, so the
   several queries behind one screen see the same moment. A ticket can never appear both waiting
   and called.

**Design details to mention:**
- **`LockedQueue` as proof.** Its constructor is package-private, so only `QueueLockService.lock`
  creates one. Methods such as `TokenAllocator.nextToken` require it, which makes "forgot the lock"
  a compile error.
- **Refresh with the lock.** `lock` calls `EntityManager.refresh(queue, PESSIMISTIC_WRITE)`, so the
  queue's data is re-read *after* the lock is granted and is never stale.
- **No deadlocks.** Each business transaction locks one queue row (member changes lock one
  organization row), so there is no circular wait.
- **Bounded waiting.** `lock_timeout` is 5 seconds and the transaction timeout is 15 seconds. A
  request that waits too long gets `503 QUEUE_BUSY` instead of hanging.
- **Idempotent booking.** The page sends an `Idempotency-Key` (one UUID per chosen slot). A repeat
  with the same key returns the first booking instead of creating another.

**Why not the alternatives?**
- **`synchronized` in Java** protects one JVM only; a second instance would bypass it.
- **Optimistic locking (`@Version`)** fails one of two conflicting writers, which then has to
  retry; on a busy queue that means many retries.
- **SERIALIZABLE isolation** also needs retry loops.

A short row lock makes requests wait their turn, and the code stays simple
([ADR 0003](adr/0003-transactional-locking-for-tokens.md)).

**Proof.** `ConcurrencyIT` holds N threads at a `CountDownLatch` and releases them together, so
the requests really overlap. Then it checks the database:
- 30 walk-ins get exactly tokens 1 to 30.
- 15 people booking a 3-place slot give exactly 3 bookings.
- 8 simultaneous Call next presses, over 10 rounds, give exactly one call per round.
- 10 identical booking submissions give one row.
- A forced rollback sends no event.

## 6. Queue logic

**Ticket states** (diagram 08):
- BOOKED: an appointment reserved the token.
- WAITING: checked in, or a walk-in.
- CALLED, then SERVING, then COMPLETED.
- CALLED can also end as NO_SHOW.
- BOOKED or WAITING can end as CANCELLED.

Final states never change, so a cancelled ticket cannot come back. The rules live in
`TicketStatus` and `QueueTicket`, and CHECK constraints back them up.

**Order of the line.**
1. Priority set by staff: High, then Normal, then Low.
2. Check-in time.
3. Creation time and id, as tie-breakers.

Appointment holders join the line when they check in (from 60 minutes before the slot), so
booking early does not overtake people who are already there. One SQL ordering
(`SERVICE_ORDER`) is used by the board, the positions and Call next, so what staff see is exactly
who gets called.

**Position** is never stored. It is the ticket's place in the ordered waiting list, computed when
read. A stored number would go wrong whenever someone ahead leaves or is re-prioritized.

**Wait estimate** (`WaitTimeEstimator`, [queue-algorithms.md](queue-algorithms.md)):

```
estimated wait = time left at the counter + people ahead × average service time
```

- **Average service time:** the mean of the last 20 completed services. Each is clamped between 1
  minute and 3 × the queue's typical time. With fewer than 3 completed services, the typical time
  is used instead.
- **Time left at the counter:**
  - nobody at the counter: 0
  - someone called but not started: one full average
  - someone being served: the average minus the time already spent, never below 0

Worked example (typical time 10 minutes, no history yet): G-001 has been served for 4 minutes,
and G-002 and G-003 are waiting. You get G-004, so your wait is 6 + 2 × 10 = **26 minutes**.
`QueueLifecycleIT` checks this exact case.

**Notifications.** After every queue change, the first three waiting visitors who have not been
told yet get one "your turn is near" message. Being called sends "it's your turn". A partial
unique index allows only one such message per ticket.

**Slots.** Slots start at opening time and repeat every slot length. A slot is offered only if it
ends by closing time, so 09:00 to 17:00 with 15-minute slots gives 32 slots, the last at 16:45. A
booking must be in the future, at most 14 days ahead, and exactly on the grid. Capacity is
counted under the queue lock, and a cancellation frees the place at once.

## 7. Live updates

**SSE vs WebSockets vs polling** ([ADR 0004](adr/0004-server-sent-events.md)):
- **SSE:** updates flow only from server to browser. It is plain HTTP, uses the same cookie, passes
  through proxies, and the browser reconnects by itself.
- **WebSockets** add a two-way protocol the app does not need.
- **Polling** is either slow or wasteful, and it keeps a sleeping free database awake.

**Rules that keep it safe and simple** ([realtime-design.md](realtime-design.md)):
- **Hints only.** Events carry only ids, a version and a reason (`queue-changed`). Private data is
  always fetched through authorized REST calls, so a broadcast cannot leak anything.
- **Refresh on connect.** Every connection starts with a `ready` event, and the page then reloads
  its data. A missed event during a network drop therefore does no harm.
- **Heartbeats** every 20 seconds keep proxies from closing idle streams and detect dead
  browsers.
- **Backoff.** If the stream fails, the client reconnects with growing delays (1 to 30 seconds).
  The "Live / Reconnecting…" label tells the user.
- **One sender thread.** It writes to all connections, so a slow browser never blocks a request.
- **Connection cap.** A user can have at most 10 open streams.

**Known limit.** The connection registry lives in one server process. With two servers, a change
on one would not reach browsers connected to the other. The fix is to publish through PostgreSQL
`LISTEN/NOTIFY` or Redis; the business code would not change
([ADR 0005](adr/0005-single-instance-event-registry.md)).

## 8. Security and multi-tenancy

See [security.md](security.md).

**Login.**
- **Password storage:** bcrypt hashes, never returned or logged.
- **Sessions:** a server-side session stored in PostgreSQL, referenced by an HTTP-only,
  SameSite=Lax cookie (Secure in production). The session id changes at login, which prevents
  session fixation.
- **Logout** really ends the session.
- **Nothing in the browser:** no token is kept in localStorage.

**Why not JWT?** A JWT kept in localStorage can be stolen by any injected script, and it is hard
to revoke before it expires ([ADR 0006](adr/0006-sessions-in-postgresql.md)).

**CSRF.** Another website could make the browser send a request with our cookie. Every write must
therefore carry the `X-XSRF-TOKEN` header, equal to the readable `XSRF-TOKEN` cookie. Another site
cannot read our cookie, so it cannot build the header.

**Roles.**
- Visitors register themselves.
- STAFF and ADMIN are memberships of one organization, granted by that organization's admin.
- A platform admin only creates organizations and cannot see their data.
- The `Permission` enum lists what each role may do, and `PermissionTest` checks every pair.

**Tenant isolation.** The organization is never taken from the client. A service works it out from
the resource (ticket to queue to organization), then calls
`AccessControlService.require(user, organization, permission)`. An admin of one organization who
puts another organization's queue id in a request is refused (`TenantIsolationIT`). Private
resources of other users, such as tickets and appointments, answer `404`, so ids cannot be probed.

**Abuse limits.**
- Login failures: 5 per email and 20 per IP in 15 minutes.
- Registrations: 10 per IP per hour.
- Lock and transaction timeouts.
- At most 10 live streams per user.

**Input.** Every write DTO is validated, and DTOs contain only fields the client may set. Status,
token, role and organization can never be set from a request.

## 9. Frontend

- **Single-page app.** React Router maps addresses to pages (`router.tsx`). Protected pages are
  wrapped in `RequireAuth`, which sends anonymous visitors to the login page and returns them
  afterwards.
- **Server data.** TanStack Query keeps a cache entry per piece of server data (for example
  `['queue', id]`). A mutation, or a live event, *invalidates* the entries it affects, and the
  query re-fetches whatever is on screen. There is no hand-written global store.
- **API client.**
  - `api/client.ts` sends cookies, adds the CSRF header to writes, and turns error responses
    (RFC 9457 problem details, each with a `code`) into an `ApiError`.
  - Pages show `errorMessage(error)`.
  - A `401` on a protected query marks the user as logged out, so the guards redirect.
- **Live hooks.** `realtime/hooks.ts` opens an `EventSource` per queue on screen, plus one for
  personal notifications. Toasts and the unread badge come from the personal stream.
- **Time.** All times are shown in the organization's time zone (`lib/format.ts`), whatever the
  visitor's device is set to.
- **Interface.** A small hand-made set of components (`components/ui.tsx`):
  - plain bordered panels, tables for lists, and statuses as a coloured dot with a word
  - IBM Plex fonts, with tokens in a monospace font
  - no icon or component library, which keeps the bundle small and the look consistent
- **Accessibility:**
  - every field has a label
  - dialogs use the native `<dialog>`
  - the live position is in an `aria-live` region
  - status messages use `role="status"` or `role="alert"`

## 10. The diagrams and what to say about each

All diagrams are in [diagrams/README.md](diagrams/README.md), with PNGs in `diagrams/png/` for
slides.

| No. | Diagram | What to say |
|---|---|---|
| 01 | System architecture | one browser app, one Spring Boot process with modules, one PostgreSQL; REST for requests, SSE for pushes |
| 02 | ER diagram | the seven tables and their keys; point at the ticket constraints |
| 03 | Booking sequence | lock the queue, validate the slot, count capacity, insert the appointment and ticket together, commit, then push |
| 04 | Token generation | why `MAX + 1` is safe: the lock serializes, the unique constraint backs it up |
| 05 | Call next | lock, check nobody is at the counter, take the first waiting ticket in service order, notify after commit |
| 06 | Live update | the event is only a hint; browsers re-fetch through authorized APIs |
| 07 | Deployment | GitHub to Render (Docker) to Neon; one container, free plans, environment variables for secrets |
| 08 | Ticket state machine | the legal transitions; final states never change |
| 09 | Use case | the four actors and what each can do; an organization admin can also do everything staff can |
| 10 | DFD level 0 | the whole system as one process and the data each actor sends and receives |
| 11 | DFD level 1 | eight processes and six data stores; change events reach process 7.0 only after commit |
| 12 | Class: domain model | entities, enums and multiplicities; in code the links are id columns |
| 13 | Class: services | who calls whom; `LockedQueue` as the proof of locking; the event path to SSE |
| 14 | Activity: one visit | walk in or book, check in, wait, called, served; the cancel, no-show and expiry exits |

**ER vs class diagram.** The ER diagram is about tables, keys and constraints. The class diagram
is about Java types, enums and responsibilities.

**DFD vs the rest.** A DFD shows where data flows and is stored, not the order of calls. Sequence
diagrams show the order.

## 11. Testing, deployment and honest limits

**Tests** ([testing.md](testing.md)). Each layer proves something different:

| Suite | Count | What it proves |
|---|---|---|
| Unit tests (`*Test`, no database) | 70 | state machine, wait arithmetic, slot grid, booking rules, permission matrix, throttling |
| Integration tests (`*IT`, real PostgreSQL) | 70 | the whole app over HTTP like the browser (cookies and CSRF), plus tenant isolation, booking, the lifecycle, real time, the constraints and concurrency |
| Browser tests (Playwright) | 6 | real user journeys, including a live update between two browsers |
| Smoke test (`scripts/smoke-test.sh`) | 36 checks | any deployed copy works end to end; `--after-restart` proves the data persisted |

**Why not H2?** H2, an in-memory database, locks rows differently and lacks partial indexes, so a
passing H2 test would not prove the PostgreSQL guarantees.

**Test setup.**
- Tests run against a separate `queueless_test` database, rebuilt from the migrations on every
  run.
- A controllable clock lets tests move time.
- The test JVM runs in India time on purpose, so time-zone bugs cannot hide.

**Deployment** ([deployment.md](deployment.md)).
- **One container.** The `Dockerfile` builds the React app with Node, packages it into the Spring
  Boot jar with Maven, and runs the jar on a slim Java runtime.
- **Render.** `render.yaml` describes the free web service. Render builds the image on every push
  to `main`.
- **Neon.** It hosts PostgreSQL.
- **Secrets.** These are environment variables set in Render, never stored in Git: the database
  address (`DATABASE_URL`) and the demo password.
- **Startup.** Flyway migrates the schema at startup, then the demo data is seeded once.
- **Health check.** `/actuator/health/liveness` does not touch the database.
- **Sleep.** The free service sleeps after 15 minutes without visitors and takes about a minute to
  wake.

**Limitations and future work.** Be open about these; it shows judgement.
- **Live push** works on one server instance only. Several instances would need `LISTEN/NOTIFY`
  or Redis.
- **The login throttle** is in memory, per instance.
- **One counter per queue.** Several counters would need a counter id on tickets.
- **No SMS or email.** It would be done with an outbox table and a provider.
- **No password reset or email verification.**
- **No audit log** of who changed what. Only cancellations record who did them.
- **No load test,** so no throughput figure is claimed. Per-queue writes are serialized but take
  milliseconds each, far faster than a real counter serves people.

## 12. Likely questions with short answers

### About the project

1. **What does QueueLess do?** It lets organizations run queues online. Visitors book slots or
   take walk-in tokens and follow their live position and wait. Staff call and serve from a live
   board; admins configure queues and staff. Organizations are isolated from each other.
2. **Who are the users?** Visitors, staff, organization admins and the platform admin (diagram
   09).
3. **How did you plan the work?** In nine phases, recorded in `docs/roadmap.md`:
   1. repository skeleton
   2. backend foundation and schema
   3. identity, organizations and security
   4. queues, appointments and tokens
   5. staff operations, estimates, notifications and live updates
   6. frontend
   7. tests
   8. production package and deployment
   9. documentation

   Each phase ended with its tests passing, so the model was iterative and incremental.
4. **What was the hardest part?** Making simultaneous actions safe (section 5), and the time-zone
   bug found by the smoke test (section 4).

### Architecture

5. **Explain the architecture in one minute.** A React single-page app talks to one Spring Boot
   application over HTTPS: REST for requests, SSE for live pushes. The backend is a modular
   monolith (auth, organizations, memberships, queues, appointments, tickets, notifications,
   real time), with PostgreSQL as the only source of truth.
6. **Why a monolith and not microservices?** Booking must save the appointment, its token and a
   notification together, which is one local transaction in one process. Microservices would add
   network calls and distributed consistency problems with no benefit at this size. The modules
   have clear boundaries, so one could be split out later.
7. **Why PostgreSQL rather than MongoDB or MySQL?** The design needs row locks, partial unique
   indexes, generated columns and CHECK constraints. PostgreSQL has all of them, and the free Neon
   plan hosts it ([ADR 0002](adr/0002-postgresql-for-consistency.md)).
8. **What is a DTO and why use records?** A DTO is a plain object that defines exactly what goes
   over the API. Java records are short, immutable and validated. Returning entities could leak
   fields such as the password hash.
9. **What does Flyway do?** It applies the numbered SQL migrations in order at startup and records
   which ran. A checksum stops anyone editing a migration that was already applied. Hibernate
   only checks that the entities match.

### Concurrency

10. **How do you guarantee unique token numbers?** Each allocation locks the queue row
    (`SELECT … FOR UPDATE`) and then computes `MAX + 1` and inserts. The next request waits for the
    lock and then sees the committed ticket. The unique constraint `uq_tickets_token` is the
    final guard. Thirty simultaneous walk-ins get exactly 1 to 30.
11. **Two people book the last place at once. What happens?** Both try to lock the queue row, so
    one waits. The first counts the bookings, finds room and commits. The second counts again,
    finds the slot full and gets `409 SLOT_FULL`.
12. **Why not `synchronized`?** It protects only one JVM. Another instance or process would bypass
    it; a database lock covers them all.
13. **Why pessimistic rather than optimistic locking?** Optimistic locking makes one of two
    conflicting writers retry. On a busy queue that means many retries. A short lock simply
    queues the requests, and the code has no retry loops.
14. **Can deadlocks happen?** Not between business transactions. Each locks one queue row, so
    there is no circular wait. Lock waits stop after 5 seconds with `503 QUEUE_BUSY`.
15. **Two staff press Call next together?** The lock serializes them, and the second sees someone
    already at the counter (`409`). Independently, the partial unique index allows only one ticket
    CALLED or SERVING per queue and day.
16. **What isolation level do you use?** PostgreSQL's default READ COMMITTED for writes, with
    explicit row locks. Reads that build one screen run read-only at REPEATABLE READ, so all their
    queries see the same moment.
17. **A user double-clicks Confirm booking?**
    - The button is disabled while the request runs.
    - The same `Idempotency-Key` returns the existing booking.
    - The one-active-booking-per-day index forbids a second booking anyway.
18. **What is idempotency?** An operation is idempotent when repeating it has the same effect as
    doing it once. Retries and double clicks are then harmless.
19. **Why send events only after commit?** If the event went out and the transaction then rolled
    back, browsers would show a change that never happened.
    `@TransactionalEventListener(AFTER_COMMIT)` runs only for committed work, and a test forces a
    rollback to prove it.

### Queue logic

20. **How is the line ordered? Is it fair?** By staff-set priority, then check-in time, then
    creation time and id. Visitors cannot raise their own priority. Booked visitors join when they
    check in, so they do not overtake people already present.
21. **Why is the position not stored?** It changes whenever anyone ahead leaves, is called or is
    re-prioritized. Computing it at read time from one ordering means it is always right.
22. **How is the wait estimated?** Time left at the counter + people ahead × average service time.
    The average is the mean of the last 20 completed services, clamped, with the queue's typical
    time used until there are 3 samples. It is labelled as an estimate.
23. **Explain the ticket lifecycle.** BOOKED, then WAITING, CALLED, SERVING and COMPLETED. CALLED
    can end as NO_SHOW; BOOKED or WAITING can end as CANCELLED. Final states never change.
24. **How do appointments and tickets stay in step?** They are created in one transaction. Every
    ticket change updates the appointment in the same transaction, through `AppointmentTicketSync`.
    A unique constraint and a composite foreign key keep one ticket per appointment, in the same
    queue.
25. **What happens at the end of the day?** Tokens restart from 1 for each service date. Tickets
    from a past day that never finished are shown as expired.

### Live updates

26. **Why SSE instead of WebSockets or polling?** Pushes go one way only. SSE is plain HTTP with
    the same cookie and reconnects automatically. WebSockets add a two-way protocol the app does
    not need, and polling is slow or wasteful.
27. **What if an event is lost?** Nothing breaks. Events are hints, and every reconnect starts
    with `ready`, after which the page reloads its data from the database.
28. **Can live events leak data?** No. They carry only ids, a version and a reason. Names and
    details come from authorized REST calls.
29. **Does it work with several servers?** Not yet for live push, because the connection registry
    is per process. Sessions and data are already shared in PostgreSQL. The fix is a gateway over
    `LISTEN/NOTIFY` or Redis.

### Security

30. **Can an admin of B edit A's queue by changing the id?** No. The service works out the queue's
    organization from the database and checks the caller's role in *that* organization. B's admin
    has none, so the request is refused.
31. **Why 404 for some requests and 403 for others?** Someone else's ticket or appointment answers
    404, so ids cannot be probed. Public things such as queues answer 403, because they are visible
    anyway.
32. **Why sessions and not JWT?** An HTTP-only cookie cannot be read by scripts. Sessions in
    PostgreSQL can be ended at logout and survive restarts. JWTs in localStorage are exposed to
    XSS and hard to revoke.
33. **What is CSRF and how do you stop it?** Another site could trigger requests with our cookie.
    Writes need a header copied from a cookie, and other sites cannot read our cookies.
    SameSite=Lax adds a second barrier.
34. **How are passwords stored?** As bcrypt hashes, salted and slow on purpose, never returned or
    logged. Failed logins are throttled per email and per IP.
35. **Can a visitor make themselves staff?** No. Registration ignores role fields. Roles are
    memberships that only an organization admin can create, and priority changes require STAFF.

### Database

36. **Walk me through the schema.** See section 4. Ids are UUIDs, instants are `timestamptz`, and
    CHECK constraints guard statuses and lifecycle timestamps.
37. **Justify one index.** `ix_tickets_waiting_order (queue_id, service_date, priority_rank,
    checked_in_at, created_at, id) WHERE status = 'WAITING'` matches the Call next query exactly.
    Being partial keeps it small. `priority_rank` is a generated column because an index cannot
    order by a CASE expression.
38. **What is a partial unique index?** A unique index over only the rows that match a condition.
    `UNIQUE (queue_id, service_date) WHERE status IN ('CALLED','SERVING')` means at most one person
    at the counter, while any number of tickets can be waiting or completed.
39. **Why UUIDs instead of 1, 2, 3 ids?** They cannot be guessed or counted from the outside, and
    they can be created without asking the database.
40. **How are time zones handled?** Instants are stored in UTC. Hours and dates are local values
    read in the organization's time zone, and the display uses that zone too.

### Frontend

41. **Why TanStack Query and not Redux?** Almost all state is server data. TanStack Query caches
    it, tracks loading and error states, and re-fetches when data is invalidated. A global store
    would duplicate the server.
42. **How does a page update live?** The SSE hook receives `queue-changed` and invalidates the
    queue's queries. TanStack Query then re-fetches what is on screen.
43. **How does the frontend handle errors?** The server sends problem details with a stable
    `code`. The client turns them into an `ApiError`, and pages show a readable message, with a
    link where it helps (for example "Open it" for an existing ticket).

### Testing and deployment

44. **How did you test concurrency?** Against real PostgreSQL. N threads wait at a latch and start
    together, then the test checks the database: tokens 1 to N, exactly capacity bookings, one call
    per round, one row per idempotency key.
45. **Why not H2?** Different locking, no partial indexes. A pass there would prove nothing about
    PostgreSQL.
46. **How is it deployed?** Push to GitHub. Render builds the Dockerfile into one container and
    runs it; Neon hosts PostgreSQL; secrets are environment variables. All are free plans.
47. **What happens if the database is down?** Requests fail cleanly with an error, and nothing is
    half-saved because everything is transactional. When the database returns, the app carries on
    with no repair.
48. **How many users can it handle?** No load test was run, so no number is claimed. Writes to one
    queue are serialized but take milliseconds, different queues run in parallel, and reads use
    indexes. The free 0.1-CPU instance would be the first limit.
49. **What would you do next?** Multi-instance live updates, several counters per queue,
    SMS/email through an outbox, an audit log, password reset, and a load test.
50. **What did you learn?** Answer in your own words. Good points: let the database enforce the
    rules, keep the lock small, test races for real instead of assuming, and be honest about
    limits.

## 13. Words to know

| Term | Meaning here |
|---|---|
| Token | the visible number on a ticket, for example `G-007` (prefix plus number) |
| Ticket | one person's place in one queue on one day |
| Service date | the day a ticket belongs to, in the organization's time zone |
| Slot | a bookable time window of fixed length |
| Check-in | the moment a booked visitor joins the line |
| Tenant | one organization; tenants never see each other's data |
| Transaction | a group of database changes saved together or not at all |
| Row lock | `SELECT … FOR UPDATE`; other transactions wait to change that row |
| Isolation level | how much one transaction sees of others running at the same time |
| Partial index | an index over only the rows matching a condition |
| Generated column | a column computed by the database from other columns |
| Idempotent | safe to repeat; the second call changes nothing more |
| SSE | Server-Sent Events: a long HTTP response through which the server pushes messages |
| CSRF | cross-site request forgery: another site making the browser send requests with our cookie |
| DTO | data transfer object: the exact shape sent over the API |
| SPA | single-page application: the browser loads one page and React swaps screens |
| Migration | a numbered SQL file that changes the schema, applied once by Flyway |

## 14. A one-week study plan

| Day | Do | Read |
|---|---|---|
| 1 | Run the app and use every role ([user guide](user-guide.md)) | section 1 |
| 2 | Open each module and its tests | section 2, [architecture.md](architecture.md), diagrams 01, 09, 12, 13 |
| 3 | Trace "Get a token" through the code | section 3, diagrams 04 and 06 |
| 4 | Read the migrations; find each constraint | section 4, [database-design.md](database-design.md), diagram 02 |
| 5 | Read `ConcurrencyIT` and run it | section 5, [concurrency-and-transactions.md](concurrency-and-transactions.md) |
| 6 | Read `WaitTimeEstimator` and the SSE hooks | sections 6 and 7, diagrams 08, 10, 11, 14 |
| 7 | Rehearse the demo and answer section 12 aloud | sections 8 to 12 |
