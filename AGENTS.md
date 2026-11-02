# Instructions for coding agents

QueueLess is a Spring Boot 3.5 (Java 21) + React 19 (TypeScript, Vite) queue and appointment
system on PostgreSQL. Read [docs/roadmap.md](docs/roadmap.md) before changing anything: it lists
the phases, invariants, conventions and the pitfalls already hit in this codebase.

> **Unfinished work:** read [NEXT_STEPS.md](NEXT_STEPS.md) first. The UI redesign in progress
> is on the `ui-redesign` branch.

## Commands

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; use a JDK 21 elsewhere
scripts/local-postgres.sh start                     # PostgreSQL on localhost:5433

cd backend && ./mvnw test                           # unit tests (no database)
cd backend && ./mvnw verify                         # unit + integration + concurrency (needs PostgreSQL)
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # API on :8080 with demo data
cd frontend && npm run dev                          # UI on :5173 (proxies /api to :8080)
cd frontend && npm run build                        # type check + production build
cd backend && ./mvnw -DskipTests package            # jar that also serves the React build
cd frontend && npx playwright test                  # E2E against the jar (starts it if needed)
QUEUELESS_DEMO_PASSWORD='Password123!' scripts/smoke-test.sh http://localhost:8080
```

## Rules

- Every change keeps `./mvnw verify`, `npm run build` and `npx playwright test` green. Run them;
  never claim results you did not see.
- Schema changes: add a new `backend/src/main/resources/db/migration/V<n>__*.sql`. Never edit an
  applied migration. Hibernate only validates.
- Writes that touch a queue's tickets or appointments must go through a service method that
  obtains `QueueLockService.lock(queueId)` first and accepts `LockedQueue` where needed.
- Authorization: derive the organization from the resource and call
  `AccessControlService.require(userId, organizationId, Permission.X)`. Never trust an
  organization id from the client. Other users' private resources answer 404.
- Controllers return DTOs (records), never entities. Validate every write DTO.
- Publish real-time changes only as events handled after commit; payloads carry ids only.
- Time comes from `TimeProvider`; do not call `Instant.now()` in business code.
- Do not set `spring.jpa.properties.hibernate.jdbc.time_zone` (it shifts `LocalTime`).
- Integration tests use real PostgreSQL (`queueless_test`), never H2.
- No secrets in the repository; use environment variables (`.env.example` lists them).

## Where things are

| Need | Look at |
|---|---|
| Module layout and dependencies | `docs/architecture.md` |
| Tables, constraints, indexes | `docs/database-design.md`, `db/migration` |
| Endpoints and status codes | `docs/api-documentation.md`, `/swagger-ui.html` |
| Locking and races | `docs/concurrency-and-transactions.md`, `ConcurrencyIT` |
| Ordering, positions, estimates | `docs/queue-algorithms.md`, `WaitTimeEstimator` |
| SSE | `docs/realtime-design.md`, `realtime/` |
| Roles and permissions | `docs/security.md`, `membership/domain/Permission.java` |
| Deployment | `docs/deployment.md`, `Dockerfile`, `render.yaml` |
