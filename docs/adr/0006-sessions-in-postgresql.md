# ADR 0006: Cookie sessions stored in PostgreSQL

- **Status:** accepted
- **Date:** 2026-10-29

## Context

The directive requires email/password login with secure HTTP-only cookies and no credentials in
localStorage. The free host sleeps after 15 minutes idle and may restart at any time; in-memory
sessions would log everybody out on each restart.

## Decision

Use Spring Security with a server-side session (cookie `QUEUELESS_SESSION`: HttpOnly, SameSite=Lax,
Secure in production) and store sessions with Spring Session JDBC in PostgreSQL (tables created by
Flyway `V4`). CSRF protection uses the SPA double-submit cookie pattern. Organization roles are not
cached in the session; they are read per request.

## Consequences

- Logins survive restarts, redeploys and sleep; logout and expiry are enforced server-side.
- The application instance is stateless, ready for horizontal scaling.
- One session read/update per request (same region, small).
- The session cleanup job runs every 6 hours in production so it does not keep Neon awake.
- Changing the serialized principal class incompatibly requires clearing `spring_session`.

## Alternatives considered

- **JWT in localStorage:** forbidden by the directive (XSS exposure); revocation is hard.
- **JWT in an HTTP-only cookie:** stateless, but logout/revocation and role changes need extra
  machinery, and CSRF protection is still required.
- **In-memory sessions:** every free-tier restart would log users out.
