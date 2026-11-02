# Deployment

**Status (2 Nov 2026): not yet deployed.** Everything up to the account-specific steps is done
and verified locally: the production jar was run with the `prod` profile against a fresh
PostgreSQL database and passed the 36-check smoke test and the 4-check persistence-after-restart
test (see [testing.md](testing.md)). Deploying needs your GitHub, Neon and Render accounts; the
exact steps are in the [checklist](#step-by-step-checklist-manual-steps) below. After deploying,
fill in the [deployment record](#deployment-record).

## Target architecture

```mermaid
flowchart LR
    Dev["Developer"] -- "git push" --> GH["GitHub"]
    GH -- "auto-deploy on commit" --> Build["Render Docker build"]
    Build --> Svc
    subgraph Render["Render free web service (Singapore)"]
        Edge["HTTPS edge"] --> Svc["Spring Boot jar + React build<br/>0.1 CPU, 512 MB"]
    end
    subgraph Neon["Neon free PostgreSQL (AWS Singapore)"]
        PG[("PostgreSQL, TLS, direct endpoint")]
    end
    Users["Browsers"] -- "HTTPS: pages, REST, SSE" --> Edge
    Svc -- "JDBC over TLS, pool max 5" --> PG
```

One container serves the React build, the REST API and the SSE streams from one origin; the
database is a separate managed service. Source: [`diagrams/07-deployment-architecture.mmd`](diagrams/07-deployment-architecture.mmd).

## Provider evaluation (checked 29 Oct 2026)

**Render — application host (chosen).** From Render's documentation
([free instances](https://render.com/docs/free), [compute plans](https://render.com/docs/compute-plans),
[web services](https://render.com/docs/web-services), [Docker](https://render.com/docs/docker)):

| Fact | Impact on QueueLess |
|---|---|
| Free web service: **0.1 CPU, 512 MB RAM**, one instance | Measured ~321 MB resident with the Dockerfile's JVM flags (heap capped at 40% of RAM) — fits with headroom. 0.1 CPU makes startup slow; the image ships a CDS archive (≈40% faster startup locally). |
| Spins down after **15 minutes** without inbound traffic; spin-up takes **about a minute**; may restart at any time | First visit after idle is slow; sessions survive because they are in PostgreSQL; SSE clients reconnect and refetch. |
| **750 free instance hours** per workspace per month | Enough for one service running all month. |
| Docker builds from a `Dockerfile`, multi-stage supported; must bind to `0.0.0.0:$PORT` (default 10000) | `server.port: ${PORT:8080}`; Tomcat binds all interfaces. |
| Ephemeral filesystem, no persistent disk on free | Nothing is stored on disk; all state is in PostgreSQL. |
| Free PostgreSQL **expires after 30 days** | Not used; Neon hosts the database. |
| Bandwidth and build minutes are included up to a limit; with **no payment method on file, overage suspends** free services instead of billing | Demo traffic is tiny. Whether sign-up asks for a card is not stated in Render's docs (third-party reports disagree); adding a card would allow overage billing — your decision. |

**Neon — PostgreSQL (chosen).** From Neon's documentation
([plans](https://neon.com/docs/introduction/plans), [connection pooling](https://neon.com/docs/connect/connection-pooling),
[Java](https://neon.com/docs/guides/java)):

| Fact | Impact |
|---|---|
| Free plan: 1 GB storage per project, **100 CU-hours per project per month**, scale-to-zero after **5 minutes** idle (cannot be disabled), wakes within a few hundred ms | The app closes idle connections after 2 minutes (`minimum-idle: 0`), never queries the database from the Render health check (liveness only), and runs the session cleanup only every 6 hours, so Neon can sleep. |
| 5 GB egress per month | Ample. |
| Pooled (`-pooler` host, PgBouncer transaction mode) vs direct; session-level features (`SET`, session advisory locks) unsupported when pooled; **direct recommended for migrations** | Use the **direct** connection: the app sets `lock_timeout` per connection and Flyway uses advisory locks. A pool of at most 5 connections needs no PgBouncer. |
| TLS required (`sslmode=require`); Neon's Java string adds `channelBinding=require` | Supported by the bundled driver (pgJDBC 42.7.11). |
| 6-hour instant-restore history, 1 manual snapshot | Take a snapshot before risky migrations. |

**Alternatives considered.** Render's free PostgreSQL expires after 30 days. Third-party
comparisons in late 2026 report that Koyeb, Google Cloud Run, Fly.io and Oracle Cloud require a
payment card to run a service for free. Cloudflare Quick Tunnels (to expose a laptop) need no
account but [do not support Server-Sent Events](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/),
which would break live updates. **No-cost fallback if Render cannot be used:** run the same jar
(or Docker image) on a laptop or lab machine for the demonstration, connected to the Neon
database, and present it on the local network:
`java -jar backend/target/queueless.jar` with the production environment variables.

## Configuration reference

| Variable | Required | Example / meaning |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | yes (set in the image) | `prod` |
| `DATABASE_URL` | yes | Neon's Java string, e.g. `jdbc:postgresql://ep-xxx.ap-southeast-1.aws.neon.tech/neondb?user=...&password=...&sslmode=require&channelBinding=require`; or JDBC without credentials plus the two variables below; or `postgresql://user:password@host/db?sslmode=require` |
| `DATABASE_USERNAME` | if not in the URL | database role |
| `DATABASE_PASSWORD` | if not in the URL | database password (secret) |
| `QUEUELESS_SEED_DEMO_DATA` | no (default `false`) | `true` to create the two demo organizations and accounts |
| `QUEUELESS_SEED_PASSWORD` | if seeding | password for all demo accounts (secret; choose your own) |
| `QUEUELESS_API_DOCS_ENABLED` | no (default `true`) | `false` hides Swagger UI |
| `PORT` | set by Render | 10000 on Render |
| `JAVA_TOOL_OPTIONS` | no (set in the image) | JVM memory flags; override to tune |

Production profile (`application-prod.yml`) also sets: secure cookies, trusting proxy headers
(`forward-headers-strategy: native`), HTTP→HTTPS redirect for proxied plain-HTTP requests,
Hikari `maximum-pool-size: 5`, `minimum-idle: 0`, `idle-timeout: 2 min`, `connection-timeout: 20 s`
(Neon wake-up), session cleanup every 6 hours.

## Step-by-step checklist (manual steps)

These steps need your accounts and secrets; they cannot be done for you. Never paste secrets
into the repository, the frontend, issues or chat.

### A. Publish the code to GitHub

1. Create an **empty** repository on GitHub (no README/licence), e.g. `queueless`.
2. In this folder:
   ```bash
   git remote add origin https://github.com/<you>/queueless.git
   ```
   ```bash
   git push -u origin main
   ```
3. Open the **Actions** tab: the CI workflow runs backend tests on PostgreSQL, the frontend
   build, the Playwright E2E tests and a Docker image build. The `docker` job is the first time
   the Dockerfile itself is built (Docker was not available on the development machine).

### B. Create the Neon database

1. Sign up at neon.com (free plan) and create a project, region **AWS Asia Pacific (Singapore)**
   (closest to Render's Singapore region), PostgreSQL 17 (16+ works).
2. Click **Connect** → choose **Java** → turn **Connection pooling off** (the host must *not*
   contain `-pooler`) → copy the JDBC string. Keep it private.
3. Optional: in **SQL Editor** run `select version();` to confirm the database is reachable.

### C. Create the Render service from the Blueprint

1. Sign up at render.com and connect your GitHub account.
2. **New → Blueprint** → **Connect** the `queueless` repository → keep branch `main` →
   Render reads `render.yaml` (free Docker web service `queueless`, Singapore, health check
   `/actuator/health/liveness`).
3. When asked for the `sync: false` values, enter:
   - `DATABASE_URL` = the Neon JDBC string from B.2
   - `DATABASE_USERNAME`, `DATABASE_PASSWORD` = leave empty if the string already contains
     `user=` and `password=` (Neon's Java string does)
   - `QUEUELESS_SEED_PASSWORD` = a demo password you choose (share it with evaluators separately)
4. **Deploy Blueprint.** The first build takes several minutes (npm, Maven, CDS training run).
   Watch the logs for `Successfully applied 4 migrations` and `Started QueueLessApplication`.
   Startup on 0.1 CPU can take much longer than locally; that is expected.
5. Note the URL, e.g. `https://queueless.onrender.com` (Render may add a suffix).

### D. Verify the deployment

```bash
QUEUELESS_DEMO_PASSWORD='<your demo password>' scripts/smoke-test.sh https://<your-app>.onrender.com
```

Expect `Passed: 36 Failed: 0`. Then:

1. **Browser:** open the URL, log in as `asha@example.com`, join *General OPD*; in a private window
   log in as `staff@citycare.example`, open **Staff → General OPD** and press **Call next** until
   Asha is called: her page must change to "It's your turn!" without reloading.
2. **Hosted database really used:** in Neon's SQL Editor run
   `select count(*) from users;` and `select email, created_at from users order by created_at desc limit 3;`
   — the smoke-test customer (`smoke-<timestamp>@example.com`) must appear. Also
   `select version, description, success from flyway_schema_history;` (4 rows, all true).
3. **Restart persistence:** restart the service from the Render dashboard (the service's
   **Manual Deploy** menu offers a restart/redeploy), or simply wait for it to sleep, then:
   ```bash
   QUEUELESS_DEMO_PASSWORD='<your demo password>' scripts/smoke-test.sh https://<your-app>.onrender.com --after-restart
   ```
   Expect `Passed: 4 Failed: 0` and, in the logs, `Schema "public" is up to date. No migration necessary.`
4. **HTTPS only:** `curl -sI http://<your-app>.onrender.com` should redirect to `https://`.
5. **Health:** `curl https://<your-app>.onrender.com/actuator/health` → `"status":"UP"` with `"db":{"status":"UP"}`.

### E. Before a demonstration

- Open the site 2–3 minutes early: a sleeping free instance needs about a minute to wake plus JVM
  startup. Keep one tab open during the viva (each page load counts as traffic).
- Keeping it awake permanently with an external pinger is possible within 750 hours/month for one
  service, but keeps Neon awake too (CU-hours) — not recommended.

## Migrations in production

- Flyway runs automatically at startup and applies only new `V<n>__*.sql` files, inside the
  deploy. A failed migration stops startup, so the new version never becomes healthy; Render
  documents zero-downtime deploys for Docker web services (traffic moves only to an instance that
  started), which should keep the previous version serving — verify this on your service, since
  free instances have fewer guarantees.
- Never edit an applied migration (checksums are validated); add a new version instead.
- Prefer additive, backward-compatible changes (add column → deploy code → backfill → constrain),
  because during a deploy the old and new versions briefly run side by side.
- Take a Neon snapshot before destructive changes. `flyway clean` is disabled outside tests.
- To reset the *demo* database deliberately: in Neon create a new branch or drop and recreate the
  schema from the SQL Editor (`drop schema public cascade; create schema public;`), then restart
  the service; Flyway rebuilds the schema and the seeder recreates demo data. Never do this to
  real data.

## Operations

- **Logs:** Render dashboard → Logs. Every line carries a request id; error responses return the
  same id (`requestId`), which makes support requests traceable.
- **Health:** liveness for the platform; `/actuator/health` (with the database) for humans.
- **Scaling:** the free plan is one instance. More instances would require the shared event
  channel described in [realtime-design.md](realtime-design.md).
- **Cost control:** no paid resources are created by `render.yaml`. Neon's free plan has no billing
  unless you upgrade.

## Deployment record

Fill this in after step D (no secrets):

| Item | Value |
|---|---|
| Public URL | _pending_ |
| Deployed commit | _pending_ |
| Render region / plan | Singapore / free |
| Database | Neon free, AWS Singapore, PostgreSQL ___ (direct endpoint) |
| Environment variables set | `SPRING_PROFILES_ACTIVE`, `DATABASE_URL`, `QUEUELESS_SEED_DEMO_DATA`, `QUEUELESS_SEED_PASSWORD` |
| Smoke test (36 checks) | _pending_ |
| After-restart check (4 checks) | _pending_ |
| Browser live-update check | _pending_ |
| Hosted database verified in Neon SQL Editor | _pending_ |
| Cold-start time observed | _pending_ |

## Known limitations of the free deployment

- Cold starts after 15 minutes of inactivity (about a minute plus JVM startup on 0.1 CPU).
- Single instance: in-memory SSE registry and login throttling (by design for this tier).
- Render may restart free services at any time; sessions and data survive, open SSE streams
  reconnect.
- Neon's free compute pauses after 5 minutes idle; the first query afterwards takes a little longer.
