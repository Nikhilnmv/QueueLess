# QueueLess

QueueLess is a web application for running service queues and appointments online, made for
clinics, college offices and help desks.
- **Visitors** book a slot or take a token for today, then follow their place in line and
  estimated wait as it updates live. They are notified when their turn is near and when they are
  called.
- **Staff** run the counter from a live board.
- **Administrators** set up queues, hours, slot sizes and staff.

Each organization sees only its own data.

| Visitors | Staff | Administrators |
|---|---|---|
| find a service and see live waiting numbers | live board: who is at the counter, the waiting line, expected bookings | create and edit queues: hours, slot length, capacity, typical service time |
| take a token for today (`G-007`) | call next, start, complete, mark no-show | add members by email and set their roles |
| book an appointment slot, check in on arrival, cancel | open, pause or close the queue | edit the organization profile |
| follow position and estimated wait live, get notified | register walk-ins without an account, change priority | (platform admin) create organizations |

**Built with:** Java 21, Spring Boot 3.5, PostgreSQL, Flyway, React 19, TypeScript, Vite,
Tailwind CSS, TanStack Query, Server-Sent Events and Playwright. It is hosted on Render and Neon
(free plans).

## Run it

You need JDK 21, Node 22 or newer, and the PostgreSQL 17 server (`brew install postgresql@17`).

```bash
scripts/run-local.sh
```

Open http://localhost:8080 and log in with a demo account. The password is `Password123!`.

| Email | Role |
|---|---|
| `asha@example.com` | visitor |
| `staff@citycare.example` | staff, City Care Clinic |
| `admin@citycare.example` | administrator, City Care Clinic |
| `platform@queueless.test` | platform admin |

The [user guide](docs/user-guide.md) lists every account and covers each role, a demo script for a
presentation, and troubleshooting.

## Tests

```bash
cd backend && ./mvnw verify                 # 70 unit + 70 integration and concurrency tests (PostgreSQL)
cd frontend && npm run build                # type check and production build
cd frontend && npx playwright test          # 6 browser tests against the packaged app
```

[docs/testing.md](docs/testing.md) has the results and what each suite covers.

## Deploy

One container (`Dockerfile`) serves both the React build and the API. `render.yaml` describes a
free Render web service, and the database is a free Neon PostgreSQL. The steps are in
[docs/deployment.md](docs/deployment.md).

## Documentation

| Document | What it covers |
|---|---|
| [User guide](docs/user-guide.md) | running it, demo accounts, each role, demo script |
| [Study guide](docs/study-guide.md) | the whole project explained for the viva, with likely questions |
| [Diagrams](docs/diagrams/README.md) | architecture, ER, use case, DFD, class, sequence, state and activity diagrams (PNGs in `docs/diagrams/png`) |
| [Architecture](docs/architecture.md) | modules, request flows, trade-offs |
| [Database design](docs/database-design.md) | tables, constraints, indexes, time zones |
| [API](docs/api-documentation.md) | every endpoint, its roles and status codes (also at `/swagger-ui.html`) |
| [Queue algorithms](docs/queue-algorithms.md) | tokens, ordering, positions, wait estimates |
| [Concurrency](docs/concurrency-and-transactions.md) | locking, race conditions, invariants, concurrency tests |
| [Real time](docs/realtime-design.md) | Server-Sent Events: lifecycle, security, reconnection |
| [Security](docs/security.md) | sessions, CSRF, roles, tenant isolation |
| [Testing](docs/testing.md) | test suites, results, bugs found |
| [Deployment](docs/deployment.md) | Render and Neon setup and checks |
| [Scalability](docs/scalability-and-tradeoffs.md) | limits, and how it would scale |
| [Decisions](docs/adr/) | six architecture decision records |
| [Roadmap](docs/roadmap.md) | build-from-scratch plan: phases, tasks, checks, pitfalls |

## Project layout

```
backend/    Spring Boot application: auth, organization, membership, queue, appointment,
            ticket, notification, realtime, common, seed; Flyway migrations; tests
frontend/   React application; Playwright tests in e2e/
scripts/    run-local.sh, local-postgres.sh, smoke-test.sh, render-diagrams.mjs
docs/       guides, design documents, decision records, diagrams
```
