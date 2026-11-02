# Testing

Everything below was **executed**; nothing is reported as passing without a run. Results are
from the local development machine (macOS, Apple Silicon, JDK 21.0.8, PostgreSQL 17.9, Node 26,
Chromium 156 via Playwright 1.64).

## Summary of the latest runs

| Suite | Command | Result |
|---|---|---|
| Backend unit tests | `cd backend && ./mvnw test` | **70 passed**, 0 failed, 0 skipped |
| Backend integration + concurrency (real PostgreSQL) | `cd backend && ./mvnw verify` | **70 passed**, 0 failed, 0 skipped (whole build ≈ 22 s) |
| Frontend type check + production build | `cd frontend && npm run build` | passed (TypeScript 7.0.2, Vite 8.3.5; after the UI redesign 439 KB JS / 130 KB gzipped, 33 KB CSS) |
| End-to-end (Playwright, Chromium, packaged app) | `cd frontend && npx playwright test` | **6 passed** |
| Smoke test against the production jar (prod profile, fresh database) | `scripts/smoke-test.sh http://localhost:8081` | **36 / 36 checks passed** |
| Persistence after restart | `scripts/smoke-test.sh http://localhost:8081 --after-restart` | **4 / 4 checks passed** |
| Mermaid diagrams | `node scripts/render-diagrams.mjs` (Mermaid 12.2, headless Chromium) | **14 / 14** render |

The unit, integration, build and Playwright rows were last run on 2 Nov 2026 on branch
`ui-redesign`, after the UI redesign (`./mvnw verify`: BUILD SUCCESS in 21 s). The smoke-test rows
are from the runs described below.

Skipped tests: none. Not executed: `docker build` (Docker is not installed on the development
machine; Render builds the image) and tests against the hosted Render + Neon deployment (it needs
the account steps in [deployment.md](deployment.md)).

## Final verification from a clean checkout (2 Nov 2026)

The repository was cloned into an empty directory and verified exactly as a new contributor or CI
would, at commit `5848f0b`:

| Step | Result |
|---|---|
| `./mvnw verify` (backend, real PostgreSQL) | 70 unit + 70 integration tests passed, BUILD SUCCESS in 23 s |
| `npm ci && npm run build` (frontend) | passed |
| `./mvnw -DskipTests package` | jar with the React build (`BOOT-INF/classes/static`) |
| `npx playwright test` (Playwright started the jar itself) | 6 passed |
| Production-container simulation: jar extracted, CDS archive trained without a database, Dockerfile JVM flags, `prod` profile, `TZ=UTC`, empty database, Neon-style URL | migrations applied, demo data seeded, started in 2.1 s |
| `scripts/smoke-test.sh` against it | **36 / 36 passed**; 323 MB resident afterwards |
| Restart, then `--after-restart` | Flyway "up to date", started in 1.8 s, **4 / 4 passed**; demo data not duplicated |

The Dockerfile was simplified afterwards (commit `2ccc7be`): a plain three-stage build without the
CDS archive. With its settings (prod profile, its JVM flags, a JDBC URL with embedded credentials)
the smoke test passed 36 / 36 again; the app used 312 MB resident and started in 4.7 s.

## How the backend is tested

- **Unit tests** (`*Test`, Surefire, no database, < 1 s): pure logic — state machines, wait-time
  arithmetic, slot grid, booking rules, permission matrix, throttling, URL conversion.
- **Integration tests** (`*IT`, Failsafe): the whole application on a random port with an HTTP
  client that behaves like the SPA (cookies + CSRF header), against a **real PostgreSQL**
  database. H2 is deliberately not used: the guarantees under test depend on PostgreSQL row
  locks, partial unique indexes, generated columns and CHECK constraints.
- **Schema from scratch:** before the tests, Flyway cleans and re-applies every migration on the
  `queueless_test` database (refusing any database whose name does not end in `_test`), so each
  run proves the migrations apply cleanly.
- **Controlled time:** a mutable clock bean (default 10:00 in Asia/Kolkata on a Tuesday) lets tests
  move time to check in early/late, expire tickets or unlock throttled accounts.
- **Non-UTC JVM on purpose:** the integration JVM runs with `-Duser.timezone=Asia/Kolkata` so that
  time-zone conversion bugs cannot hide on UTC machines (see `TimeZoneStorageIT`).
- **Isolation between tests:** every test creates its own organizations/queues/users with unique
  names, so tests do not depend on each other or on order.

### Unit tests (70)

| Class | Tests | Covers |
|---|---:|---|
| `TicketStatusTest` | 10 | complete transition table (every from→to pair), terminal states, cancelled can never re-enter |
| `QueueTicketTest` | 6 | factories, lifecycle timestamps, illegal transitions rejected with `INVALID_STATE_TRANSITION`, priority only before the counter, rank values match the SQL generated column |
| `WaitTimeEstimatorTest` | 7 | default vs history basis, mean, outlier clamping, invalid durations, remaining time at the counter (none/called/serving/overrun), rounding up, positions list |
| `SlotScheduleTest` | 4 | slot count and last slot, partial slot excluded, no midnight wrap, alignment rules |
| `BookingRulesTest` | 6 | future-only, 14-day horizon, grid alignment, organization time zone interpretation (New York vs Kolkata), required time |
| `PermissionTest` | 21 | every permission × role (matrix in security.md), non-members, role inclusion |
| `AttemptLimiterTest` | 4 | lockout after the limit, unlock after the lockout, window reset, reset |
| `DatabaseUrlEnvironmentPostProcessorTest` | 4 | Neon URL → JDBC with encoded credentials, `channel_binding` removed, port kept, JDBC URLs untouched, explicit credentials win |
| `AppointmentTest` | 3 | lifecycle, idempotent cancellation releasing capacity, finished appointments cannot be cancelled |
| `ServiceQueueTest` | 4 | new queues start CLOSED, what each status allows, token display format, version increments |
| `BookingReferenceGeneratorTest` | 1 | unambiguous alphabet, low collision rate |

### Integration tests (70)

| Class | Tests | Covers |
|---|---:|---|
| `AuthIT` | 11 | registration (normalized email, bcrypt hash stored, no hash returned, session started), sessions stored in PostgreSQL, registration cannot grant roles, duplicate email (case-insensitive) 409, field errors, one generic login error, login returns memberships, logout ends the session, CSRF required, 401 for protected endpoints, account lockout after 5 failures and unlock after 15 min, platform endpoint forbidden to org admins |
| `TenantIsolationIT` | 8 | other organization's staff: 403 on board/call-next/status/walk-in, 404 on tickets; other admin: 403 on overview/profile/members/queues; a queue id from another org is refused; customers cannot see/cancel/check in each other's bookings (404); customers cannot use staff operations or raise priority; staff cannot manage members or configure queues; member management incl. last-admin rule and cross-org membership ids |
| `BookingIT` | 12 | slot grid in the org time zone, booking reserves a unique token and consumes capacity, full slot 409, cancellation releases capacity and is idempotent, cancelled ticket cannot re-enter, idempotency key replay (200) and reuse for another slot (422), one booking per day, invalid slots 400, booking disabled 409, check-in window, staff check-in/cancel on behalf with notification, cannot cancel once at the counter, "my appointments" flags |
| `QueueLifecycleIT` | 14 | consecutive tokens and one active ticket per customer, tokens restart each day, OPEN/PAUSED/CLOSED rules, full lifecycle and every illegal transition, one ticket at the counter and no-show freeing it, **priority then check-in ordering** for both the board and call-next, positions and estimates (worked example 26 min), switch to history-based average, approach/called/no-show notifications, notifications persist across logins and only the owner can read them, leaving the queue, guest walk-ins, expired tickets, queue creation/validation/reconfiguration |
| `ConcurrencyIT` | 10 | see the table in [concurrency-and-transactions.md](concurrency-and-transactions.md#9-how-this-is-tested-executed): unique tokens under 30 simultaneous walk-ins, capacity under 15 simultaneous bookings, last place to exactly one of 10, call-next never assigns twice (10 rounds × 8 threads), duplicate submissions, same-day duplicates, idempotent concurrent cancellation, call-next vs cancel race, concurrent registrations, rollback publishes nothing |
| `RealtimeIT` | 5 | SSE `ready` then `queue-changed` after commit with no customer data, heartbeats, personal `notification` events, 401 without session / 404 for unknown queue, closed connections removed from the registry |
| `MigrationAndConstraintsIT` | 9 | all migrations applied in order; direct SQL proves the database rejects duplicate tokens, two tickets at the counter, invalid statuses/priorities, missing lifecycle timestamps, a second ticket for an appointment, a ticket pointing at another queue's appointment, non-normalized emails, two active bookings per day; `priority_rank` generated correctly |
| `TimeZoneStorageIT` | 1 | `LocalTime`/`LocalDate` stored verbatim in a non-UTC JVM (regression test, see below) |

### End-to-end tests (Playwright, 6)

Run against the packaged jar (Spring Boot serving the React build) with demo data:

1. A customer registers, discovers City Care Clinic → General OPD, books the first free slot
   tomorrow, sees it on the dashboard, cancels it with confirmation, then joins today's line and
   sees token and position.
2. Anonymous visitors to `/dashboard` are redirected to `/login?next=/dashboard`.
3. Unknown routes show the not-found page.
4. **Live update across browsers:** an admin creates an empty queue; a customer in a second
   browser context joins (token E-001, position 1, "Live" indicator); staff call next on the
   board; the customer's page shows "It's your turn!" and a notification toast **without
   reloading**; start and complete update both screens; the notification is listed afterwards.
5. Staff of Northbridge College cannot open a City Care board ("do not have permission").
6. An admin creates a queue in the dialog, adds a registered user as STAFF, and is prevented from
   demoting the organization's only admin.

### Smoke test (`scripts/smoke-test.sh`, 36 checks + 4 after restart)

Runs against any deployment URL. Executed against the **production jar with the `prod` profile**
on a fresh database (Flyway from zero, demo seed with a custom password, Neon-style
`postgresql://user:pass@host/db` URL to exercise the URL conversion; passed with a 256 MB heap and
again with the Dockerfile's flags and a 192 MB heap):
health with DB, liveness, SPA index and deep-link fallback, unknown API paths (401 anonymous / 404
logged in), discovery of both organizations, registration/logout/login, wrong password, booking
with idempotency replay, capacity consumed and released by cancellation, idempotent repeat
cancellation, admin creates a queue, staff open it, walk-in, three parallel walk-ins get unique
tokens, call-next, second call-next blocked (409), start, complete, SSE connected and delivered
≥ 4 `queue-changed` events, no customer data in SSE, TURN_CALLED stored and visible after
re-login, cross-organization 403/404. After stopping and restarting the server,
`--after-restart` confirmed the customer could log in and their notification, completed ticket and
cancelled appointment were still there (Flyway: "Schema is up to date. No migration necessary").

### Manual checks in a browser

Landing, login (header switches to the signed-in state), organization page with live counts,
queue page with Live indicator, walk-in ticket page; with the ticket open, a staff call from
another session moved the position from #4 to #3 and showed a toast without reloading; staff
board (counter, Call next disabled with reason, waiting line, priority change reordering the line,
start/complete/call next); admin organization page, members tab and last-admin error.

After the UI redesign (2 Nov 2026) these screens were checked again in a browser: home, organization,
queue, login, My visits, ticket, staff board, admin queues and members, and access denied.

## Bugs found by testing (and fixed)

| Found by | Bug | Fix |
|---|---|---|
| `RealtimeIT` | `StackOverflowError` when subscribing: the SSE `Subscription` was a Java record containing the map it was stored in, and the record's generated `hashCode()` recursed through the map | identity-based class |
| Smoke test on the production jar | `hibernate.jdbc.time_zone=UTC` shifted `LocalTime` by the JVM offset (09:00 stored as 03:30); invisible to Hibernate round trips, it broke when a range crossed midnight | setting removed; `TimeZoneStorageIT` in an IST JVM; mutation-checked (the test fails if the setting is restored) |
| `AuthIT` | emails with surrounding spaces failed `@Email` before normalization | DTOs trim before validation |
| Browser check | after login the header still showed "Log in": clearing the whole query cache detached mounted queries | identity switch removes only private queries |
| `MigrationAndConstraintsIT` (test data) | fixtures violated other constraints first (a SERVING row without `service_started_at`) | consistent fixtures, one constraint per test |
| Test design review | with a frozen clock, tickets had equal check-in times, making the order depend on random UUIDs | tests advance the clock per join |

## Measurements (local, informative only)

| Measurement | Value | How |
|---|---|---|
| Startup (prod profile, local DB) | ~3.1 s; 4.7 s with the current Dockerfile's JVM flags | `Started QueueLessApplication in ...` |
| Resident memory after smoke load | ~312 MB with the current Dockerfile's flags | `ps -o rss` |
| Backend full build with all tests | ~22 s | Maven |

These are single-machine observations, not benchmarks; throughput and latency targets for the
hosted service are not claimed. Expect much slower cold starts on a 0.1-CPU free instance.

## Known gaps

- Load testing (sustained throughput, latency percentiles) was not performed.
- Browser coverage: Chromium only.
- Accessibility checked by structure (labels, roles, live regions) and keyboard use, not with an
  automated audit tool.
- Hosted deployment verification is pending account setup ([deployment.md](deployment.md)).

## Running the tests yourself

```bash
scripts/local-postgres.sh start            # PostgreSQL on localhost:5433
cd backend && ./mvnw verify                 # unit + integration + concurrency
cd ../frontend && npm ci && npm run build   # type check + production build
cd ../backend && ./mvnw -DskipTests package # jar with the React build
cd ../frontend && npx playwright install chromium && npx playwright test
QUEUELESS_DEMO_PASSWORD='Password123!' ../scripts/smoke-test.sh http://localhost:8080
```

Use JDK 21 (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)` on macOS).
