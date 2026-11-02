# QueueLess build roadmap

A complete, ordered plan for building QueueLess from an empty folder. It is written so that an
AI coding agent (or a developer) can follow it phase by phase and end up with this repository:
same architecture, same guarantees, same tests. The code in this repository is the reference
implementation of every step; the design documents in `docs/` explain the reasoning.

**Status of this repository (2 Nov 2026):** phases 0–6 and 8 complete and verified; phase 7
(deployment) prepared and verified locally with the production jar, waiting for the account steps
in [deployment.md](deployment.md#step-by-step-checklist-manual-steps).

---

## 0. How to use this roadmap

### Operating rules for agents

1. **Work in phases, in order.** Each phase ends with acceptance checks. Do not start the next
   phase until every check passes. Commit after each phase.
2. **Evidence before claims.** Never report that something works, a test passes or a deployment
   succeeded without running it and seeing the output. Report skipped work and why.
3. **Never weaken guarantees to make something pass.** Do not disable security, validation,
   tests or database constraints. Fix the cause.
4. **Correctness lives in the database.** PostgreSQL is the source of truth. Every invariant gets
   a service check *and* a database constraint where expressible.
5. **Real PostgreSQL for integration tests.** Not H2.
6. **Verify versions and provider limits** against current registries and official docs before
   pinning them; do not trust memory or old tutorials.
7. **Secrets** only in environment variables; placeholders in `.env.example`; nothing secret in
   git, the frontend bundle, docs or chat.
8. **Keep docs truthful:** distinguish implemented, tested and future behaviour.

### Legend

`[ ]` task · **AC** acceptance check (command → expected result) · ⚠ pitfall seen during the
reference build

---

## 1. Product scope

**Users and roles.** Customers (any registered user); organization-scoped STAFF and ADMIN
(memberships); optional PLATFORM_ADMIN who only provisions organizations.

**Must have:**

1. Email/password registration, login, logout; secure HTTP-only cookie sessions; CSRF protection.
2. Multiple organizations with strict tenant isolation (roles checked per organization on every
   protected operation).
3. Queues per organization: create, configure (hours, slot length, capacity per slot, default
   service time, token prefix, booking on/off), open, pause, close.
4. Appointment slots, booking (capacity-safe, idempotent), cancellation (releases capacity),
   check-in.
5. Automatic unique tokens per queue and day; walk-ins (self-service and staff-registered guests).
6. Staff operations: call next, start service, complete, no-show, change priority (staff only).
7. Live position and waiting-time estimates computed by the server.
8. Real-time updates without refresh (SSE), reconnect-safe.
9. Persistent in-app notifications (turn approaching, called, booking confirmed, cancelled by
   staff, no-show).
10. Separate customer, staff and admin interfaces; not-found and access-denied pages.
11. Flyway migrations, hosted PostgreSQL (Neon), deployment (Render), automated tests,
    concurrency tests, documentation.

**Out of scope:** SMS/WhatsApp/email, payments, mobile apps, AI scheduling, Redis,
microservices, Kubernetes.

---

## 2. Technology decisions (pinned and verified on 29 Oct 2026)

| Area | Choice | Version used | Why |
|---|---|---|---|
| Language/runtime | Java | 21 (Temurin/Oracle) | LTS; virtual-thread era; CDS |
| Framework | Spring Boot | 3.5.16 (Spring 6.2, Security 6.5, Hibernate 6.6) | directive; latest 3.5.x patch |
| Build | Maven + wrapper | 3.9.16 | reproducible builds (`./mvnw`) |
| Database | PostgreSQL | 17 locally; Neon in production | locks, partial indexes, generated columns |
| Migrations | Flyway | 11.7 (managed by Boot) + `flyway-database-postgresql` | versioned schema |
| Sessions | Spring Session JDBC | 3.5 | sessions survive restarts |
| API docs | springdoc-openapi | 2.8.17 | Swagger UI |
| Frontend | React + TypeScript + Vite | React 19.3, TS 7.0, Vite 8.3 | directive |
| Styling / icons | Tailwind CSS (Vite plugin) / Lucide | 4.3 / 1.63 | |
| Routing / data | React Router / TanStack Query | 8.5 / 5.105 | `createBrowserRouter`; cache + invalidation |
| E2E | Playwright | 1.64 (Chromium) | browser tests |
| Hosting | Render free web service (Docker) + Neon free | — | verified limits in deployment.md |

Do **not** add: Redux, Lombok, Redis, a message broker, ESLint type-aware plugins that do not
support TypeScript 7.

---

## 3. Target repository layout

```
QueueLess/
├── AGENTS.md                  short instructions for coding agents (points here)
├── README.md                  overview, quick start, demo accounts
├── Dockerfile                 multi-stage: Node build -> Maven package -> JRE + CDS
├── .dockerignore  .gitignore  .env.example  Makefile  render.yaml  docker-compose.yml
├── .github/workflows/ci.yml   backend tests (PostgreSQL), frontend build, E2E, docker build
├── scripts/
│   ├── local-postgres.sh      project-local PostgreSQL on :5433 (no Docker)
│   ├── smoke-test.sh          36 end-to-end checks against any URL, plus --after-restart
│   └── docker-init/01-test-database.sql
├── backend/
│   ├── pom.xml  mvnw  .mvn/wrapper/
│   └── src/
│       ├── main/java/com/queueless/
│       │   ├── QueueLessApplication.java
│       │   ├── common/{config,error,time,web}
│       │   ├── auth/{api,domain,security,service}
│       │   ├── organization/{api,domain,service}
│       │   ├── membership/{api,domain,service}
│       │   ├── queue/{api,domain,service}
│       │   ├── ticket/{api,domain,service}
│       │   ├── appointment/{api,domain,service}
│       │   ├── notification/{api,domain,service}
│       │   ├── realtime/
│       │   └── seed/
│       ├── main/resources/
│       │   ├── application.yml  application-dev.yml  application-prod.yml
│       │   ├── META-INF/spring.factories
│       │   └── db/migration/V1..V4__*.sql
│       └── test/
│           ├── java/com/queueless/{support,...}   *Test (unit), *IT (integration)
│           └── resources/application-test.yml
├── frontend/
│   ├── package.json  vite.config.ts  tsconfig*.json  playwright.config.ts  index.html
│   ├── e2e/*.spec.ts
│   └── src/{api,auth,components,lib,pages,realtime}  main.tsx  router.tsx  index.css
└── docs/
    ├── roadmap.md (this file)  architecture.md  database-design.md  api-documentation.md
    ├── queue-algorithms.md  concurrency-and-transactions.md  realtime-design.md  security.md
    ├── deployment.md  testing.md  scalability-and-tradeoffs.md  viva-preparation.md
    ├── adr/0001..0006-*.md
    └── diagrams/*.mmd + README.md
```

---

## 4. Conventions (apply in every phase)

**Backend**

- Package by module, then `api` / `service` / `domain`. Constructor injection only.
- Controllers: validate DTOs (`@Valid` records), take the user via
  `@AuthenticationPrincipal AuthenticatedUser`, call **one** service method, map to DTOs. No
  business rules, no repositories for coordinated writes.
- Services: `@Transactional` boundaries, authorization, locking, orchestration. Pass the acting
  user id explicitly (testable without a security context).
- Entities: no public setters for invariants; state changes through methods (`ticket.call(now)`).
  UUID ids (`GenerationType.UUID`), `Instant` for instants, `LocalDate`/`LocalTime` for
  organization-local values, `@Enumerated(STRING)`.
- Time comes from `TimeProvider` (injectable `Clock`, truncated to microseconds).
- Errors: throw `ApiException(ErrorCode, detail)`; `GlobalExceptionHandler` produces RFC 9457
  problem details with a `code`. Constraint violations are translated by name
  (`ConstraintErrors`).
- Modules reference each other by id. Allowed dependency direction:
  `common ← auth ← membership ← queue ← ticket ← appointment`, `organization` under membership,
  `realtime`/`notification` used by queue/ticket/appointment. Break cycles with interfaces owned
  by the lower module (`MembershipDirectory`, `TicketLifecycleListener`).
- Comments explain business rules and locking decisions, not syntax.

**API**

- Everything under `/api`; JSON; `201` + `Location` for creation; `400/401/403/404/409/422/429/503`
  as defined in [api-documentation.md](api-documentation.md).
- Private resources of other users → `404`; public resources without the role → `403`.
- Writes need `X-XSRF-TOKEN`. Bookings accept `Idempotency-Key`.

**Frontend**

- All HTTP through `src/api/client.ts` (`credentials: same-origin`, CSRF header, `ApiError`).
- Server state only via TanStack Query hooks in `src/api/queries.ts` with hierarchical keys
  (`['queue', id, ...]`) so one SSE event invalidates everything about a queue.
- Never compute permissions or business numbers in the UI; show server values and flags
  (`canCancel`, `canCheckIn`, positions, estimates).
- Disable buttons while their mutation is pending. Labels on every input; `aria-live` for live
  numbers.

**Testing**

- Unit tests `*Test.java` (Surefire, no DB). Integration tests `*IT.java` (Failsafe, real DB).
- Each test creates its own uniquely named data. Control time through the mutable clock.

---

## 5. Invariants (must never be violated)

| # | Invariant | Service enforcement | Database enforcement |
|---|---|---|---|
| I1 | Token unique per queue and service date | `MAX+1` under queue lock | `UNIQUE (queue_id, service_date, token_number)` |
| I2 | Slot bookings ≤ capacity | count non-cancelled under queue lock | (not expressible) — concurrency tests |
| I3 | ≤ 1 ticket CALLED/SERVING per queue and day | check before calling | partial unique index |
| I4 | ≤ 1 active ticket per customer, queue, day | check before insert | partial unique index |
| I5 | ≤ 1 active booking per customer, queue, day | check before insert | partial unique index |
| I6 | Appointment has ≤ 1 ticket, in the same queue | created together | unique + composite FK |
| I7 | Only legal state transitions; timestamps match status | `TicketStatus`, entity methods | CHECK constraints |
| I8 | Cancelled tickets never re-enter | terminal state | CHECK + no transition |
| I9 | Users act only inside organizations where they hold the role | `AccessControlService.require` on resource-derived org | — |
| I10 | Events only for committed changes | `@TransactionalEventListener(AFTER_COMMIT)` | — |
| I11 | Broadcast events carry no customer data | ids-only payload records | — |
| I12 | An organization always keeps ≥ 1 admin | check under organization lock | — |
| I13 | Duplicate booking submission → one booking | idempotency lookup under lock | `UNIQUE (user_id, idempotency_key)` |
| I14 | Emails unique case-insensitively | normalize | unique + CHECK lower(trim) |

---

## 6. Environment setup

```bash
# Tools: JDK 21, Maven (wrapper downloads it), Node >= 22.22, PostgreSQL 16+ server binaries, git.
java -version; node -v; psql --version
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; on Linux point JAVA_HOME at JDK 21

scripts/local-postgres.sh start      # project-local cluster on localhost:5433 (dbs queueless, queueless_test)
# or with Docker: docker compose up -d db
```

⚠ macOS: `pg_ctl` fails with "postmaster became multithreaded during startup" unless
`LC_ALL=C` (the script sets it).
⚠ zsh does not split unquoted variables: pass JVM flags through `JAVA_TOOL_OPTIONS`, not `$FLAGS`.

---

## Phase 0 — Repository skeleton

**Goal:** an empty but well-structured repository.

- [ ] `git init -b main`; `.gitignore` (secrets, `.local/`, `target/`, `node_modules/`, `dist/`,
      Playwright output, `.smoke-state`, `.claude/`).
- [ ] `scripts/local-postgres.sh` (initdb with SCRAM auth, start on :5433 with
      `max_connections=150`, create role `queueless`/`queueless`, databases `queueless` and
      `queueless_test`; subcommands `start|stop|status|reset-dev|reset-test|psql`).
- [ ] `docker-compose.yml` (postgres:17-alpine, 5433:5432, init script creating `queueless_test`).
- [ ] `.env.example` with placeholders only.

**AC:** `scripts/local-postgres.sh start && pg_isready -h localhost -p 5433` → accepting connections.

---

## Phase 1 — Backend foundation and schema

**Goal:** the application boots against PostgreSQL with the full schema migrated and validated.

### Tasks

- [ ] `backend/pom.xml`: parent `spring-boot-starter-parent:3.5.16`; dependencies web, security,
      data-jpa, validation, actuator, `spring-session-jdbc`, `flyway-core`,
      `flyway-database-postgresql`, `postgresql` (runtime), springdoc 2.8.17; test: starter-test,
      spring-security-test, awaitility. `finalName` `queueless`. Resources: `src/main/resources`
      **and** `../frontend/dist` with `targetPath static`. Enforcer `[21,)`. Surefire includes
      `**/*Test.java`; Failsafe includes `**/*IT.java` with
      `-XX:+EnableDynamicAgentLoading -Duser.timezone=Asia/Kolkata`.
- [ ] Maven wrapper: `mvn -N wrapper:wrapper -Dmaven=3.9.16`.
- [ ] `application.yml` (production-safe defaults): datasource from `DATABASE_URL/USERNAME/PASSWORD`;
      Hikari `connection-init-sql: SET lock_timeout = '5s'`; `open-in-view: false`;
      `ddl-auto: validate`; **no `hibernate.jdbc.time_zone`**; Flyway on; session timeout 8h,
      `initialize-schema: never`; `spring.transaction.default-timeout: 15s`;
      `server.port: ${PORT:8080}`; cookie `QUEUELESS_SESSION` http-only, same-site lax, secure;
      error details off; actuator exposes `health,info`, probes on, liveness group = livenessState,
      readiness group = readinessState + db; `queueless.*` settings (see Appendix B).
- [ ] `application-dev.yml` (local DB defaults on :5433, insecure cookie for http, seed enabled
      with `Password123!`), `application-prod.yml` (Hikari max 5 / min idle 0 / idle 2 min /
      keepalive 0 / connect 20 s, session cleanup every 6 h, `forward-headers-strategy: native`,
      `https-redirect: true`, seed via env), test `application-test.yml` (test DB, pool 40,
      `flyway.clean-disabled: false`, high per-IP limits).
- [ ] `common`: `QueueLessProperties` (validated record), `DatabaseUrlEnvironmentPostProcessor`
      (registered in `META-INF/spring.factories`; converts `postgresql://user:pass@host/db?...`
      to JDBC + credentials, drops `channel_binding`), `ErrorCode`, `ApiException`, `Problems`,
      `ConstraintErrors`, `GlobalExceptionHandler`, `ProblemResponseWriter`, `TimeConfig` +
      `TimeProvider`, `RequestIdFilter`, `SpaWebConfig` (assets cached immutable; SPA fallback to
      `index.html` except `api/`, `actuator`, docs, and paths with a file extension), `OpenApiConfig`.
- [ ] Flyway migrations exactly as in `db/migration` (summary in [database-design.md](database-design.md)):
  - **V1** `users` (unique normalized email + CHECK), `organizations` (unique slug + format CHECK,
    timezone, active), `organization_memberships` (unique `(user_id, organization_id)`, role CHECK,
    index `(organization_id, role)`).
  - **V2** `service_queues` (unique `(organization_id, name)`, status CHECK, hours CHECK, ranges,
    prefix CHECK, `state_version`), `appointments` (unique reference, unique `(user_id,
    idempotency_key)`, unique `(id, queue_id)`, status CHECK, cancelled_at CHECK, partial index
    `(queue_id, scheduled_at) WHERE status <> 'CANCELLED'`, index `(user_id, status, scheduled_at)`,
    partial unique `(queue_id, user_id, service_date) WHERE status IN ('BOOKED','CHECKED_IN')`),
    `queue_tickets` (nullable `user_id` + `guest_name`, `source`, `priority_rank` **generated
    stored** column, unique token `(queue_id, service_date, token_number)`, unique
    `appointment_id`, composite FK `(appointment_id, queue_id)`, CHECKs for status, priority,
    source↔appointment, holder, positive token and every lifecycle timestamp rule; partial unique
    `(queue_id, service_date) WHERE status IN ('CALLED','SERVING')`; partial index
    `(queue_id, service_date, priority_rank, checked_in_at, created_at, id) WHERE status = 'WAITING'`;
    partial unique `(queue_id, user_id, service_date) WHERE user_id IS NOT NULL AND status IN
    ('BOOKED','WAITING','CALLED','SERVING')`; `(user_id, service_date DESC)`;
    `(queue_id, completed_at DESC) WHERE status = 'COMPLETED'`).
  - **V3** `notifications` (type CHECK, `(user_id, created_at DESC)`, partial unread index,
    partial unique `(ticket_id, type) WHERE type IN ('TURN_APPROACHING','TURN_CALLED')`).
  - **V4** Spring Session tables (copy `schema-postgresql.sql` from the spring-session-jdbc jar).

### AC

- Dry-run SQL: `(echo BEGIN; cat V*.sql; echo ROLLBACK) | psql ... -v ON_ERROR_STOP=1` → no errors.
- `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` → log shows
  `Successfully applied 4 migrations` and `Started QueueLessApplication`.
- `curl localhost:8080/actuator/health` → `"status":"UP"` with `"db":{"status":"UP"}`.

### Pitfalls

⚠ **`hibernate.jdbc.time_zone=UTC` shifts `LocalTime` by the JVM offset** (09:00 stored as
03:30 on a +05:30 machine). Round trips hide it; it explodes when a range crosses midnight.
Do not set it; `Instant` → `timestamptz` is already UTC.
⚠ Records bound with `@ConfigurationProperties` need every nested group present in `application.yml`.
⚠ `flyway clean` must be enabled only in tests, and the test config must refuse databases whose
name does not end in `_test`.

---

## Phase 2 — Identity, organizations, memberships, security

**Goal:** register/login/logout with cookie sessions and CSRF; organizations, roles and
tenant-aware authorization; demo data.

### Tasks

- [ ] `auth.domain.User` (+ repository), `EmailNormalizer` (trim + lower-case), `AuthService.register`
      (byte-length ≤ 72 check, duplicate → `EMAIL_ALREADY_REGISTERED`, `saveAndFlush`).
- [ ] `AuthenticatedUser implements UserDetails, CredentialsContainer` (id, email, name,
      platformAdmin; authorities `ROLE_USER` [+ `ROLE_PLATFORM_ADMIN`]; **no org roles**).
- [ ] `QueueLessUserDetailsService`.
- [ ] `SecurityConfig`: `CookieCsrfTokenRepository.withHttpOnlyFalse()` (SameSite Lax, secure per
      cookie config) + `SpaCsrfTokenRequestHandler` + `CsrfCookieFilter` after `CsrfFilter`;
      `DelegatingSecurityContextRepository`; request cache, form login and basic auth disabled;
      logout at `POST /api/auth/logout` → 204; JSON entry point (401) and access-denied handler
      (403, `CSRF_REJECTED` for CSRF); headers (CSP, frame deny, referrer policy);
      `dispatcherTypeMatchers(ASYNC, ERROR).permitAll()`; public GETs for discovery,
      `/api/platform/**` → `hasRole("PLATFORM_ADMIN")`, `/api/**` authenticated, actuator health/info
      only, everything else permitted (SPA); optional `redirectToHttps` for requests with
      `X-Forwarded-Proto: http`.
- [ ] `SessionAuthenticationStrategy` = change session id + `CsrfAuthenticationStrategy`;
      `SessionAuthenticator.establishSession` saves the context **and touches the new CSRF token**
      so the login response sets a fresh `XSRF-TOKEN` cookie.
- [ ] `AttemptLimiter` (fixed window, in-memory): login 5 failures/email and 20/IP per 15 min →
      15-min lock (429 + Retry-After); registration 10/IP/hour.
- [ ] `AuthController`: `POST /register` (201, auto-login), `POST /login`, `GET /me`. DTOs trim
      emails in compact constructors.
- [ ] `MembershipDirectory` port in auth; `CurrentUserService`.
- [ ] `organization`: entity (zone fixed after creation), repository (`findByIdForUpdate`),
      `OrganizationService` (public list/search, `requireActive`, `lockActive`, create),
      `OrganizationController` (`GET /api/organizations`, `/{slug}`).
- [ ] `membership`: `MembershipRole` (CUSTOMER < STAFF < ADMIN), `Permission` enum with
      minimum roles (matrix in [security.md](security.md)), `OrganizationMembership`,
      `MembershipRepository` (role lookup, member projection, `insertCustomerIfAbsent` with
      `ON CONFLICT DO NOTHING`), `AccessControlService.require/has`, `MembershipService`
      (add/change/remove under organization lock, last-admin rule), `MembershipDirectoryImpl`,
      `OrganizationAdministrationService`, `OrganizationAdminController`
      (`/api/admin/organizations/{orgId}` + `/members`), `PlatformAdminController`.
- [ ] `seed.DemoDataSeeder` (conditional on `queueless.seed.enabled`): idempotent creation of
      the platform operator, City Care Clinic (General OPD G 09:00–17:00/15 min/2 per slot; Lab
      Sample Collection L 08:00–13:00/10 min/3) and Northbridge College Office (Admissions Desk
      A; Fee Counter F), their admins and staff, three customers; refuses to run without a
      password; dev also seeds three tickets for today through the real ticket service (Phase 3).

### AC

- `curl` flow: `GET /api/auth/me` → 401 + `XSRF-TOKEN` cookie; `POST /login` without header → 403
  `CSRF_REJECTED`; with header → 200 with memberships.
- Integration tests `AuthIT` (11) and `TenantIsolationIT` (8) pass (written in this phase with the
  test support classes of Phase 6, or immediately after Phase 3 if they need tickets).

### Pitfalls

⚠ Spring Security 6 creates CSRF tokens lazily: without `CsrfCookieFilter` the SPA never gets the
cookie.
⚠ After login, `CsrfAuthenticationStrategy` clears the token; touch the new token or the next POST
fails.
⚠ SSE completion triggers an ASYNC dispatch that must be permitted, or completed streams log
access-denied errors.
⚠ Keep org roles out of the session principal (stale roles; tenant leaks).

---

## Phase 3 — Queues, appointments, tokens

**Goal:** queue configuration and status; walk-ins and bookings with unique tokens and safe
capacity; cancellation and check-in.

### Tasks

- [ ] `queue.domain`: `QueueStatus` (OPEN accepts arrivals + calling; PAUSED accepts arrivals, no
      calling; CLOSED neither), `QueueSettings` record, `ServiceQueue` (`apply`, `changeStatus`,
      `bumpStateVersion`, `acceptsNewArrivals`, `canCallNext`, `displayToken` → `G-007`; new queues
      start CLOSED), repository with scalar `findOrganizationId`.
- [ ] `queue.service.LockedQueue` (package-private constructor) and `QueueLockService.lock`:
      `find` + `refresh(entity, PESSIMISTIC_WRITE)`, `@Transactional(propagation = MANDATORY)`.
- [ ] `QueueService`: public list/get, `requireActiveQueue`, management list, create (ADMIN,
      duplicate name 409, closing > opening, one slot must fit), update under lock (+ version/event),
      `changeStatus` (STAFF) under lock. `QueueController`, `QueueManagementController`.
- [ ] `ticket.domain`: `TicketStatus` with the transition table (state diagram:
      [diagrams/08-ticket-state-machine.mmd](diagrams/08-ticket-state-machine.mmd)), `ACTIVE` and `AT_COUNTER` sets;
      `TicketPriority` (HIGH 1, NORMAL 2, LOW 3); `TicketSource`; `QueueTicket` (factories
      `forAppointment`/`walkIn`/`guestWalkIn`; transition methods set timestamps; read-only mapped
      `priorityRank`); `QueueTicketRepository` with `SERVICE_ORDER` constant used by every ordering
      query, `findMaxTokenNumber`, waiting list with `Limit`, counter lookup, ownership projection,
      board rows, service intervals, status counts.
- [ ] `TokenAllocator.nextToken(LockedQueue, LocalDate)`; `TicketService`: `joinQueue`
      (lock, accepts arrivals, one active ticket, token, membership, record change),
      `registerGuest` (STAFF), `reserveForAppointment`, `checkIn`, `cancel`/`cancelLocked`
      (idempotent, staff cancellation notifies the holder); `TicketTransitions` +
      `TicketLifecycleListener` port.
- [ ] `appointment`: `AppointmentStatus`, `Appointment` (idempotent mirror transitions),
      repository (capacity count excluding CANCELLED, slot usage, idempotency lookup, ownership
      projection), `SlotSchedule` (minutes-of-day grid, no midnight wrap), `BookingRules`
      (future, ≤ 14 days, on the grid, organization zone), `BookingReferenceGenerator`
      (`QL-` + 6 Crockford base-32), `AppointmentViews` (`canCancel`, `canCheckIn`,
      `checkInOpensAt`), `AppointmentService` (`slots`, `book`, `checkIn`, `cancel`, `get`,
      `listMine`), `AppointmentTicketSync`, `AppointmentController` (with `Idempotency-Key`).

### Booking algorithm (exact order)

1. Lock queue row. 2. Organization active. 3. If `Idempotency-Key` seen for this user: same queue
and time → return it (200 replay), else 422. 4. Booking enabled. 5. Validate slot.
6. Count non-cancelled bookings in the slot ≥ capacity → 409 `SLOT_FULL`. 7. Active booking on that
day → 409 `ALREADY_BOOKED`. 8. Insert appointment (`saveAndFlush`). 9. Reserve ticket (BOOKED,
next token). 10. Ensure CUSTOMER membership. 11. Notification `APPOINTMENT_CONFIRMED`.
12. Record change (approach notifications, version bump, event). 13. Commit.

### AC

- `BookingIT` (12) and the booking/token tests in `ConcurrencyIT` pass: 30 simultaneous walk-ins →
  tokens exactly 1..30; 15 bookings for a 3-place slot → exactly 3 succeed; 10 identical keyed
  submissions → 1 row.

### Pitfalls

⚠ A JPA locking **query** returns an already-managed entity unchanged (stale). Use `refresh` with
the lock, authorize with scalar queries first, and load entities you will modify only after
locking.
⚠ With `order_inserts` off (default) and no JPA associations, insert order = persist order; keep
appointment before ticket (FK).
⚠ Two first visits inserting the CUSTOMER membership concurrently → use `ON CONFLICT DO NOTHING`.

---

## Phase 4 — Staff operations, estimates, notifications, real time

**Goal:** the live queue works end to end and pushes updates without refresh.

### Tasks

- [ ] `QueueOperationsService`: `callNext` (authorize OPERATE_QUEUE on the queue's org, lock,
      OPEN, counter free, first waiting in service order, CALLED, **flush**, TURN_CALLED
      notification, record change), `startService`, `complete`, `markNoShow` (+ notification),
      `changePriority` (BOOKED/WAITING only). Ticket-centric ops resolve the organization through
      the ticket; non-members get 404 (403 if it is their own ticket).
- [ ] `WaitTimeEstimator` (pure): stats from last 20 completed (clamp 1 min…3× default; < 3 samples
      → default), remaining at counter, estimate = remaining + ahead × average (ceil minutes),
      positions list. `QueueMetrics` loads inputs.
- [ ] `QueueChangeRecorder.record(locked, date, reason)`: TURN_APPROACHING for the first 3 waiting
      not yet notified, bump version, publish `QueueChangedEvent`.
- [ ] `TicketViews` (ticket + live info), `QueueReadService` (`live`, `board`, `ticket`, `myTickets`)
      with `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`.
- [ ] Controllers: `TicketController` (`/live`, join, mine, get, cancel), `StaffQueueController`
      (board, call-next, walk-ins, start, complete, no-show, priority).
- [ ] `notification`: entity, repository (list with `Limit`, unread count, mark all),
      `NotificationService.notify` (`MANDATORY` transaction + `UserNotificationEvent`),
      `NotificationController` (list, read, read-all, `GET /api/me/events`).
- [ ] `realtime`: `QueueChangedEvent`, `UserNotificationEvent`, `QueueChangeReason`,
      `RealtimeGateway` (+ `SseRealtimeGateway`), `RealtimeEventRelay`
      (`@TransactionalEventListener(AFTER_COMMIT)`, ids-only payload records),
      `SseEmitterRegistry` (`compute`-based add/remove, identity-equality subscriptions, single
      sender thread, heartbeat via `TaskScheduler`, 30-min emitter timeout, `retry` 3 s, 10 streams per
      user, `SmartLifecycle` that completes streams on shutdown), `QueueEventsController`
      (`GET /api/queues/{id}/events`).

### AC

- `QueueLifecycleIT` (14), `RealtimeIT` (5) and the call-next/cancel/rollback tests in
  `ConcurrencyIT` pass: 10 rounds × 8 simultaneous call-next → exactly 1 success per round and 10
  TICKET_CALLED events total.
- Manual: with a ticket page open, calling next from another session moves the position and shows a
  toast without reloading.

### Pitfalls

⚠ **Never make a subscription a record that contains its own container map**: the generated
`hashCode()` recursed and threw `StackOverflowError` on the first subscribe.
⚠ Approach notifications must be computed after the change is flushed (JPQL queries auto-flush).
⚠ `@Scheduled(fixedRateString)` and Boot duration strings differ; schedule heartbeats with the
bound `Duration` through `TaskScheduler`.

---

## Phase 5 — Frontend

**Goal:** responsive customer, staff and admin interfaces with live updates.

### Tasks

- [ ] `frontend/package.json` (scripts: `dev`, `typecheck` = `tsc --noEmit` for app and node
      configs, `build` = typecheck + `vite build`, `test:e2e`), `vite.config.ts` (React + Tailwind
      plugins; proxy `/api`, `/actuator`, `/v3/api-docs`, `/swagger-ui` to :8080), strict
      `tsconfig.json` (bundler resolution, `verbatimModuleSyntax`) and `tsconfig.node.json`,
      `index.html`, `src/index.css` (`@import "tailwindcss"`).
- [ ] `api/types.ts` (mirrors of every DTO), `api/client.ts` (`api<T>()`, CSRF bootstrap via
      `GET /api/auth/me`, `ApiError` with `code` and `fieldErrors`), `api/queries.ts` (hooks + keys +
      `invalidateQueue` + `switchIdentity`).
- [ ] `realtime/useEventStream.ts` (EventSource; `ready` → refetch; manual reconnect with
      exponential backoff + jitter when CLOSED; reconnect on `online`) and `realtime/hooks.ts`
      (`useQueueEvents`, `useUserEvents` with toasts).
- [ ] `components/`: `ui.tsx` (Button with loading/disabled, Card, Badge, Alert, Field/Input/
      Select/TextArea, native `<dialog>` Dialog + ConfirmDialog, Spinner, EmptyState, ErrorState,
      Stat), status badges + `LiveIndicator`, `Toast` provider, `Layout` (nav by role, bell with
      unread count, personal stream, logout navigates home first), `TicketCard`, `QueueLivePanel`.
- [ ] `auth/guards.tsx` (`RequireAuth` with `next` redirect and optional role predicate),
      `auth/roles.ts`.
- [ ] Pages: Landing, Login (safe `next`), Register (client + server field errors),
      Organizations (debounced search), Organization (queue cards with live counts), Queue (live
      panel, join, book link, existing ticket link), BookAppointment (day chips, slot grid,
      confirmation, idempotency key per selected slot), Dashboard, Ticket (token, position,
      estimate, called banner, leave queue), Appointment (check in, cancel), Notifications,
      Staff home + Staff queue board (status controls, counter actions, call next with reason
      when disabled, waiting table with priority selects, expected arrivals with check-in,
      guest walk-in form, stats, ordering policy), Admin home + Admin organization (queues tab
      with dialog, members tab, profile tab), Platform, NotFound/RouteError, AccessDenied.
- [ ] `router.tsx` with `createBrowserRouter`; `main.tsx` with `QueryClient` (no retries on 4xx;
      401 on any query → signed-out state) and `RouterProvider` from `react-router/dom`.

### AC

- `npm run build` → TypeScript passes, Vite builds `dist/` (~460 KB JS).
- `./mvnw -DskipTests package` → `queueless.jar` contains `BOOT-INF/classes/static/index.html`.
- Browser walkthrough of the demo script in [viva-preparation.md](viva-preparation.md#demo-script-5-minutes) works.

### Pitfalls

⚠ **Do not call `queryClient.clear()` on login/logout**: mounted queries detach and the header kept
showing "Log in". Remove only non-`me` queries and set `['me']`.
⚠ `EventSource` gives up permanently on HTTP errors (restart, expired session); you must reconnect
yourself.
⚠ TypeScript 7 narrows `'randomUUID' in crypto` fallbacks to `never`; `crypto.randomUUID()` is
available on HTTPS and localhost.
⚠ React Router 8: `RouterProvider` for the DOM comes from `react-router/dom`.

---

## Phase 6 — Tests and quality gates

**Goal:** executable proof of every requirement and invariant.

### Tasks

- [ ] Test support (`backend/src/test/java/com/queueless/support`): `MutableClock` (primary
      `Clock` bean, default 2026-03-10 10:00 IST), `TestConfig` (clock, recording realtime gateway,
      Flyway clean+migrate guarded by `_test` name, `TestData`), `TestData` (fast fixtures through
      repositories, one bcrypt hash reused, unique names), `ApiClient` (Java `HttpClient` with
      cookie manager and CSRF header), `IntegrationTestBase` (`@SpringBootTest(RANDOM_PORT)`,
      profile `test`).
- [ ] Unit tests (70): see the table in [testing.md](testing.md#unit-tests-70).
- [ ] Integration tests (70): `AuthIT`, `TenantIsolationIT`, `BookingIT`, `QueueLifecycleIT`,
      `ConcurrencyIT`, `RealtimeIT`, `MigrationAndConstraintsIT`, `TimeZoneStorageIT`.
- [ ] Concurrency tests use a `CountDownLatch` start gate and assert database invariants plus event
      counts; any non-business exception fails the test.
- [ ] Playwright (`frontend/e2e`, workers 1): customer journey, live staff→customer update across
      two browser contexts, admin queue + members, tenant isolation, auth redirect, not found.
- [ ] `scripts/smoke-test.sh` (36 checks + `--after-restart`).
- [ ] Security review against [security.md](security.md): CSRF on all writes, no entity
      responses, no secrets in repo/bundle, error bodies without internals, headers present.

### AC

- `./mvnw verify` → `Tests run: 70` (unit) and `Tests run: 70` (integration), `BUILD SUCCESS`.
- `npx playwright test` → `6 passed`.
- Smoke test against the **prod-profile jar on a fresh database** → `Passed: 36 Failed: 0`;
  restart; `--after-restart` → `Passed: 4 Failed: 0`.

### Pitfalls

⚠ **Frozen clock + equal timestamps = order decided by random UUIDs.** Advance the clock between
joins when a test depends on order.
⚠ Every test request comes from 127.0.0.1 with a frozen clock: raise per-IP limits in the test
profile and test the limiter as a unit.
⚠ Run integration tests in a non-UTC JVM so time-zone bugs surface on UTC CI machines.
⚠ A fixture row that violates another constraint first hides the constraint under test; build
consistent rows.
⚠ Mutation-check important regression tests (re-introduce the bug, see the test fail).

---

## Phase 7 — Production package and deployment

**Goal:** a reproducible image deployed on free infrastructure and verified by URL.

### Tasks

- [ ] `Dockerfile`: `node:24-alpine` (npm ci, build) → `maven:3.9-eclipse-temurin-21`
      (`dependency:go-offline`, copy `frontend/dist`, `package -DskipTests`) →
      `eclipse-temurin:21-jre-alpine` (non-root user; `TZ=UTC`; `SPRING_PROFILES_ACTIVE=prod`;
      `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40 -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=160m
      -XX:ReservedCodeCacheSize=64m -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"`;
      `java -Djarmode=tools -jar ... extract`; CDS training run
      `-XX:ArchiveClassesAtExit=/app/app.jsa -Dspring.context.exit=onRefresh` with Flyway off,
      `ddl-auto=none`, `hibernate.boot.allow_jdbc_metadata_access=false`, explicit dialect and a
      dummy URL; entrypoint `java -XX:SharedArchiveFile=/app/app.jsa -jar /app/queueless.jar`).
- [ ] `.dockerignore`, `render.yaml` (free Docker web service, Singapore, liveness health check,
      `sync: false` secrets), `.github/workflows/ci.yml` (backend on postgres:17 service, frontend,
      E2E, docker build).
- [ ] Verify locally: prod profile + Neon-style URL + tight heap → smoke test 36/36; memory
      (~320 MB RSS); CDS startup gain (~3.1 s → ~1.8 s locally).
- [ ] Manual account steps (GitHub push, Neon direct JDBC string, Render Blueprint, secrets) —
      [deployment.md checklist](deployment.md#step-by-step-checklist-manual-steps).
- [ ] Hosted verification: smoke test against the URL, browser live update, Neon SQL Editor shows
      the smoke customer, restart + `--after-restart`, HTTPS redirect; fill the deployment record.

### AC

- CI green on GitHub (including the Docker build).
- Hosted smoke test 36/36 and after-restart 4/4; record URL and results in deployment.md.

### Pitfalls

⚠ Use Neon's **direct** endpoint (no `-pooler`): `SET lock_timeout` and Flyway's session-level
advisory locks do not work through PgBouncer transaction pooling.
⚠ Render health check must be **liveness** (no DB): a DB-touching check keeps Neon awake and can
restart the app while Neon wakes.
⚠ Hikari `minimum-idle: 0` and an infrequent session cleanup let Neon scale to zero.
⚠ Cloudflare Quick Tunnels do not support SSE — not a valid fallback host.

---

## Phase 8 — Documentation and viva preparation

- [ ] `docs/` set: architecture, database-design, api-documentation, queue-algorithms,
      concurrency-and-transactions, realtime-design, security, deployment, testing,
      scalability-and-tradeoffs, viva-preparation, this roadmap.
- [ ] ADRs 0001–0006 (monolith, PostgreSQL, locking, SSE, single-instance registry, sessions).
- [ ] Diagrams in `docs/diagrams/*.mmd` (architecture, ER, booking, token generation, call-next,
      live update, deployment, ticket state machine) and `docs/diagrams/README.md` embedding them.
- [ ] Validate every Mermaid block by rendering it (Mermaid in headless Chromium).
- [ ] README (quick start, demo accounts, commands) and AGENTS.md.

**AC:** all diagrams render (8/8); every number quoted in docs comes from an executed run.

⚠ Mermaid sequence diagrams treat `;` as a statement separator — avoid semicolons in messages and
notes.

---

## Definition of done

- [x] Working web application: Java/Spring Boot backend, React frontend, single origin.
- [x] Real PostgreSQL with Flyway migrations; data and sessions survive restarts (verified).
- [x] Registration, authentication, organization-scoped roles; tenant isolation tested.
- [x] Booking, cancellation, check-in, walk-ins; tokens safe under concurrency (tested).
- [x] Legal state transitions only (Java + CHECK constraints, tested).
- [x] Positions and waiting estimates computed by the backend.
- [x] Real-time updates without refresh (SSE, tested in integration and E2E).
- [x] Persistent notifications (tested across logins).
- [x] Unit, integration, concurrency, E2E and smoke tests executed and passing.
- [x] Architecture, database, API, algorithms, concurrency, real time, security, testing,
      scalability, viva docs; 6 ADRs; 8 diagrams.
- [x] Production package builds and passes the smoke test locally.
- [ ] Deployed and verified at a public URL (pending account steps).
- [x] Limitations stated honestly.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `postmaster became multithreaded during startup` | macOS locale | `export LC_ALL=C` (script does it) |
| App fails: `Failed to configure a DataSource` | `DATABASE_URL` missing in prod | set env vars; dev profile has defaults |
| `Schema-validation: missing table` | migrations not applied / wrong DB | check Flyway logs, `flyway_schema_history` |
| `403 CSRF_REJECTED` | missing `X-XSRF-TOKEN` | GET `/api/auth/me` first, echo the cookie |
| `503 QUEUE_BUSY` | lock wait > 5 s | retry; investigate long transactions |
| Times off by hours in the database | JVM-offset conversion | ensure `hibernate.jdbc.time_zone` is not set |
| SSE connects then 500 | exception in subscribe | check logs; registry/subscription changes |
| Browser stuck "Reconnecting" | session expired or server down | log in again; check health |
| Integration tests refuse to start: "Refusing to clean database" | test DB name lacks `_test` | point `QUEUELESS_TEST_DATABASE_URL` at a `*_test` database |
| Tests fail only on CI | different time zone/locale | tests already run in Asia/Kolkata; check clock use |
| Render deploy times out at startup | 0.1 CPU is slow | CDS archive is in the image; check logs for DB connectivity |

---

## Appendix A — Endpoint index

Auth: `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`.
Discovery: `GET /api/organizations`, `GET /api/organizations/{slug}`,
`GET /api/organizations/{slug}/queues`, `GET /api/queues/{id}`, `GET /api/queues/{id}/live`,
`GET /api/queues/{id}/slots`.
Customer: `POST /api/queues/{id}/appointments`, `GET /api/me/appointments`,
`GET /api/appointments/{id}`, `POST /api/appointments/{id}/cancel`,
`POST /api/appointments/{id}/check-in`, `POST /api/queues/{id}/tickets`, `GET /api/me/tickets`,
`GET /api/tickets/{id}`, `POST /api/tickets/{id}/cancel`.
Staff: `GET /api/staff/queues/{id}/board`, `POST /api/staff/queues/{id}/call-next`,
`POST /api/staff/queues/{id}/walk-ins`, `POST /api/staff/queues/{id}/status`,
`POST /api/staff/tickets/{id}/start|complete|no-show`, `PATCH /api/staff/tickets/{id}/priority`.
Admin: `GET|PATCH /api/admin/organizations/{orgId}`, `GET|POST /api/admin/organizations/{orgId}/queues`,
`PUT /api/admin/queues/{id}`, `GET|POST /api/admin/organizations/{orgId}/members`,
`PATCH|DELETE /api/admin/organizations/{orgId}/members/{membershipId}`.
Platform: `POST /api/platform/organizations`.
Notifications: `GET /api/me/notifications`, `POST /api/me/notifications/{id}/read`,
`POST /api/me/notifications/read-all`.
Real time: `GET /api/queues/{id}/events`, `GET /api/me/events`.
Ops: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`,
`/swagger-ui.html`, `/v3/api-docs`.

## Appendix B — Application settings (`queueless.*`)

| Key | Default | Meaning |
|---|---|---|
| `booking.max-days-ahead` | 14 | booking horizon (organization-local days) |
| `check-in.early-minutes` | 60 | check-in opens this long before the slot |
| `notifications.approach-position` | 3 | notify when position ≤ this |
| `estimation.sample-size` / `min-samples` | 20 / 3 | history for the average service time |
| `realtime.emitter-timeout` / `heartbeat-interval` / `reconnect-delay` / `max-connections-per-user` | 30m / 20s / 3s / 10 | SSE |
| `security.https-redirect` | false (true in prod) | redirect proxied plain-HTTP requests |
| `security.login.*` | 5 per email, 20 per IP, window 15m, lockout 15m | login throttling |
| `security.registration.*` | 10 per IP per 1h | registration throttling |
| `seed.enabled` / `sample-tickets` / `password` | false / false / "" | demo data |

## Appendix C — Demo accounts (seeded)

| Email | Role |
|---|---|
| `asha@example.com`, `ravi@example.com`, `meera@example.com` | customers |
| `staff@citycare.example` / `admin@citycare.example` | City Care Clinic staff / admin |
| `staff@northbridge.example` / `admin@northbridge.example` | Northbridge College Office staff / admin |
| `platform@queueless.test` | platform operator |

Password: `Password123!` locally (dev profile); the value of `QUEUELESS_SEED_PASSWORD` in production.
