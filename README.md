# QueueLess

Real-time queue and appointment management for clinics, college offices, laboratories and help
desks. Customers book a slot or take a token, watch their live position and estimated wait, and
are notified when their turn approaches and when they are called. Staff run the line from a live
board; administrators configure queues, schedules, capacity and staff. Many organizations share
one platform, each isolated from the others.

**Stack:** Java 21 · Spring Boot 3.5 (Web MVC, Security, Data JPA, Validation, Actuator, Session
JDBC) · Flyway · PostgreSQL · React 19 + TypeScript + Vite + Tailwind CSS + React Router +
TanStack Query · Server-Sent Events · Playwright · Docker · Render + Neon.

**Status:** feature-complete and tested locally (70 unit, 70 PostgreSQL integration/concurrency,
6 end-to-end, 36-check smoke test against the production jar). Hosted deployment is prepared;
it needs the account steps in [docs/deployment.md](docs/deployment.md).

## What it does

| Customers | Staff | Administrators |
|---|---|---|
| register / log in | live board: who is at the counter, waiting line in service order, expected arrivals | create and configure queues (hours, slot length, capacity, typical service time, token prefix, bookings on/off) |
| discover organizations and queues with live waiting counts | call next, start, complete, mark no-show | add members by email and set roles (customer / staff / admin) |
| book an appointment slot (safe against overbooking and double clicks) | open, pause, close the queue | edit the organization profile |
| join today's line as a walk-in and get a token (`G-007`) | register walk-ins for people without an account, with priority | (platform operator) provision new organizations |
| check in on arrival, cancel, leave the queue | change priority, check in or cancel on a customer's behalf | |
| live position and estimated wait, "it's your turn" alert, saved notifications | everything updates live | |

## Quick start (local)

Requirements: JDK 21, Node 22.22+ (Node 24 recommended), PostgreSQL 16+ server binaries
(`brew install postgresql@17`) **or** Docker.

```bash
# 1. Database on localhost:5433 (choose one)
scripts/local-postgres.sh start
docker compose up -d db

# 2. Backend with demo data on http://localhost:8080
export JAVA_HOME=$(/usr/libexec/java_home -v 21)        # macOS
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 3. Frontend with hot reload on http://localhost:5173 (new terminal)
cd frontend && npm install && npm run dev
```

Or run the production build as one process:

```bash
cd frontend && npm ci && npm run build
cd ../backend && ./mvnw -DskipTests package
java -jar target/queueless.jar --spring.profiles.active=dev   # http://localhost:8080
```

### Demo accounts (dev profile, password `Password123!`)

| Email | Role |
|---|---|
| `asha@example.com`, `ravi@example.com`, `meera@example.com` | customers |
| `staff@citycare.example` / `admin@citycare.example` | City Care Clinic staff / admin |
| `staff@northbridge.example` / `admin@northbridge.example` | Northbridge College Office staff / admin |
| `platform@queueless.test` | platform operator |

Try it: log in as Asha in one window and join *General OPD*; log in as `staff@citycare.example` in
a private window, open **Staff → General OPD** and press **Call next** — Asha's screen updates
without reloading.

## Tests

```bash
cd backend && ./mvnw test        # 70 unit tests, no database
cd backend && ./mvnw verify      # + 70 integration and concurrency tests on PostgreSQL
cd frontend && npm run build     # TypeScript check + production build
cd frontend && npx playwright install chromium && npx playwright test   # 6 E2E tests
QUEUELESS_DEMO_PASSWORD='Password123!' scripts/smoke-test.sh http://localhost:8080
```

See [docs/testing.md](docs/testing.md) for what each suite covers and the latest results.

## Deploy

One Docker image serves the React build and the API (`Dockerfile`); `render.yaml` describes a
free Render web service; the database is Neon's free PostgreSQL. Follow
[docs/deployment.md](docs/deployment.md) — it contains the verified free-tier limits, the exact
dashboard steps and the post-deployment checks. Configuration is environment-only
(see [.env.example](.env.example)).

## Documentation

| Document | Contents |
|---|---|
| [docs/roadmap.md](docs/roadmap.md) | **Build-from-scratch roadmap** for developers and AI agents: phases, tasks, acceptance checks, pitfalls |
| [docs/architecture.md](docs/architecture.md) | modules, request flows, trade-offs |
| [docs/database-design.md](docs/database-design.md) | schema, constraints, indexes, time zones, procedures |
| [docs/api-documentation.md](docs/api-documentation.md) | every endpoint, roles, status codes, examples |
| [docs/queue-algorithms.md](docs/queue-algorithms.md) | tokens, ordering, positions, wait estimates |
| [docs/concurrency-and-transactions.md](docs/concurrency-and-transactions.md) | locking, races, invariants, concurrency tests |
| [docs/realtime-design.md](docs/realtime-design.md) | SSE lifecycle, security, reconnection, limits |
| [docs/security.md](docs/security.md) | authentication, CSRF, permission matrix, tenant isolation |
| [docs/deployment.md](docs/deployment.md) | Render + Neon setup, migrations, verification |
| [docs/testing.md](docs/testing.md) | executed suites, results, bugs found, gaps |
| [docs/scalability-and-tradeoffs.md](docs/scalability-and-tradeoffs.md) | bottlenecks, scaling path, failure scenarios |
| [docs/viva-preparation.md](docs/viva-preparation.md) | likely questions with answers, demo script |
| [docs/adr/](docs/adr/) | architecture decision records 0001–0006 |
| [docs/diagrams/](docs/diagrams/README.md) | Mermaid sources: architecture, ER, sequences, deployment, state machine |
| [AGENTS.md](AGENTS.md) | rules and commands for coding agents |

## Project layout

```
backend/    Spring Boot application (modules: auth, organization, membership, queue, ticket,
            appointment, notification, realtime, common, seed), Flyway migrations, tests
frontend/   React application, Playwright tests
scripts/    local PostgreSQL, smoke test, Docker init SQL
docs/       design documentation, ADRs, diagrams
```
