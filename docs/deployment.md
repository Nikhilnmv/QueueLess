# Deployment

QueueLess runs as one Docker container on Render's free plan, with its database on Neon's free
PostgreSQL. Render rebuilds the container from the `Dockerfile` every time `main` changes.

**Status: not deployed yet.** The production build was checked locally: it ran with the `prod`
profile against a fresh database, and both the 36-check smoke test and the after-restart check
passed ([testing.md](testing.md)). The account steps below are yours to do.

![Deployment diagram](diagrams/png/07-deployment-architecture.png)

## What gets deployed

- **`Dockerfile`**, in three stages:
  1. Node builds the React app.
  2. Maven packages it into the Spring Boot jar.
  3. A slim Java 21 runtime runs the jar with the `prod` profile and memory flags that fit the
     free 512 MB instance.
- **`render.yaml`**, a Render Blueprint: one free Docker web service in Singapore with the health
  check `/actuator/health/liveness` and the variables below.
- **At startup,** Flyway applies any new migrations, then the demo data is created once (when
  enabled).

| Variable | Set by | Value |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `render.yaml` and the image | `prod` |
| `DATABASE_URL` | you, when creating the Blueprint | Neon's Java connection string, with pooling off |
| `QUEUELESS_SEED_DEMO_DATA` | `render.yaml` | `true`: create the demo organizations and accounts |
| `QUEUELESS_SEED_PASSWORD` | you | the password for every demo account; choose your own |

`DATABASE_URL` can take any of three forms:
- the Neon Java string
- a `postgresql://user:password@host/db?sslmode=require` address
- a JDBC URL without credentials, plus `DATABASE_USERNAME` and `DATABASE_PASSWORD`

Secrets are never stored in Git; `.env.example` lists the variables with placeholders.

## Steps

### 1. Code on GitHub

The repository is at https://github.com/Nikhilnmv/QueueLess. Render deploys the `main` branch, so
merge finished work into `main` first.

### 2. Create the database on Neon

1. Sign up at [neon.com](https://neon.com) (free plan). Create a project in **AWS Asia Pacific
   (Singapore)**, close to Render's Singapore region.
2. Click **Connect**, choose **Java** and turn **Connection pooling off**. The host name must not
   contain `-pooler`. Copy the connection string and keep it private.

Use the direct connection, not the pooled one. The app sets a lock timeout on each connection, and
Flyway uses session locks; neither works through Neon's pooler.

### 3. Create the service on Render

1. Sign up at [render.com](https://render.com) and connect your GitHub account.
2. Choose **New**, then **Blueprint**, and pick the QueueLess repository (branch `main`). Render
   reads `render.yaml`.
3. When asked, enter:
   - `DATABASE_URL`: the Neon string
   - `QUEUELESS_SEED_PASSWORD`: a demo password you choose
4. Click **Deploy Blueprint**. The first build takes several minutes. In the logs, look for
   `Successfully applied 4 migrations` and `Started QueueLessApplication`. Startup on the free 0.1
   CPU is much slower than on a laptop; that is normal.
5. Note the address Render gives you, for example `https://queueless.onrender.com`.

### 4. Check it

```bash
QUEUELESS_DEMO_PASSWORD='<your demo password>' scripts/smoke-test.sh https://<your-app>.onrender.com
```

Expect `Passed: 36 Failed: 0`. Then restart the service from the Render dashboard (the Manual
Deploy menu) and run:

```bash
QUEUELESS_DEMO_PASSWORD='<your demo password>' scripts/smoke-test.sh https://<your-app>.onrender.com --after-restart
```

Expect `Passed: 4 Failed: 0`. This shows the data survived the restart, because it lives in Neon.
Finally, try the live update with two browser windows ([user guide, section 8](user-guide.md#8-a-demo-for-the-presentation-about-8-minutes)).

### 5. Before a presentation

Open the site a few minutes early. A free Render service sleeps after 15 minutes without visitors
and takes about a minute to wake. Keep one tab open during the viva.

## Free plan limits (checked 29 Oct 2026)

| Service | Limit | Effect on QueueLess |
|---|---|---|
| Render | 0.1 CPU, 512 MB RAM, one instance | slow start; the app uses about 320 MB |
| Render | sleeps after 15 minutes without traffic; about a minute to wake | the first visit after a break is slow. Sessions survive (they are in PostgreSQL), and live pages reconnect |
| Render | 750 free instance hours a month | enough for one service all month |
| Neon | 1 GB storage, 100 compute hours a month, sleeps after 5 minutes idle | the app closes idle connections and its health check skips the database, so Neon can sleep |

Nothing paid is created. Without a payment method on file, Render suspends a free service that
goes over its limits instead of billing for it. Sources: [Render free
instances](https://render.com/docs/free), [Neon plans](https://neon.com/docs/introduction/plans).

## Updating and migrations

- Push to `main`, and Render rebuilds and redeploys.
- Schema changes go in a new `backend/src/main/resources/db/migration/V<n>__*.sql`, which Flyway
  applies at startup. Never edit a migration that has already been applied.
- To reset the demo data on Neon:
  1. In Neon's SQL Editor, run `drop schema public cascade; create schema public;`.
  2. Restart the service. Flyway rebuilds the schema and the demo data is created again.

  Never do this to real data.

## If Render cannot be used

Run the app on a laptop with `scripts/run-local.sh` and present from there. Do not use Cloudflare
quick tunnels to expose the laptop: they do not support Server-Sent Events, so live updates would
stop.

## Deployment record

Fill this in after step 4. Do not include any secrets.

| Item | Value |
|---|---|
| Public URL | _pending_ |
| Deployed commit | _pending_ |
| Render region and plan | Singapore, free |
| Database | Neon free, AWS Singapore, direct connection |
| Smoke test (36 checks) | _pending_ |
| After-restart check (4 checks) | _pending_ |
| Live update checked in two browsers | _pending_ |
| Time to wake after sleeping | _pending_ |
