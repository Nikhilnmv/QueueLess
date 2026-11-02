# Security

## 1. Authentication

| Aspect | Implementation |
|---|---|
| Credentials | Email + password. Emails are trimmed and lower-cased; uniqueness is case-insensitive (`uq_users_email` + CHECK). |
| Password storage | Spring Security delegating encoder (bcrypt, `{bcrypt}` prefix allows future algorithm upgrades). Plaintext is never stored or logged; the hash is never returned. Passwords are 8–72 characters and at most 72 bytes (bcrypt's limit). |
| Login | Custom JSON endpoint using Spring's `AuthenticationManager` (`DaoAuthenticationProvider`, which also performs a dummy hash check for unknown emails to blunt timing attacks). The same message is returned for unknown email, wrong password and disabled account. |
| Session | HTTP-only cookie `QUEUELESS_SESSION`, `SameSite=Lax`, `Secure` in production, 8-hour inactivity timeout. The session id is rotated at login (session-fixation protection). Sessions are stored in PostgreSQL (Spring Session JDBC), so logout and expiry are server-side. |
| Principal | `AuthenticatedUser` holds only id, email, name and the platform flag; credentials are erased after authentication. **Organization roles are not stored in the session**: they are read from the database on every protected operation, so revoking a role takes effect immediately. |
| Storage in the browser | Nothing sensitive: no tokens in localStorage or sessionStorage. The SPA cannot read the session cookie. |

## 2. CSRF

Cookie-based sessions need CSRF protection. QueueLess uses Spring Security's double-submit
pattern for single-page apps: the server sets a readable `XSRF-TOKEN` cookie; the SPA echoes it
in the `X-XSRF-TOKEN` header on every POST/PUT/PATCH/DELETE (`frontend/src/api/client.ts`).
A cross-site attacker can make the browser send the cookie but cannot read it to build the
header. Login and registration are protected too (prevents login CSRF). The token is replaced at
login. `SameSite=Lax` adds a second layer. Tested by `AuthIT.writesWithoutTheCsrfHeaderAreRejected`.

## 3. Authorization model

### Roles

| Role | Scope | How it is granted |
|---|---|---|
| Customer (any logged-in user) | own appointments, tickets, notifications | self-registration |
| CUSTOMER membership | informational: marks a customer of an organization | automatically on first booking/walk-in |
| STAFF | one organization | an ADMIN of that organization |
| ADMIN | one organization (includes STAFF) | platform provisioning or another ADMIN |
| PLATFORM_ADMIN | create organizations only; **no access to organization data** | database flag (seed); never via the API |

Public registration can never create STAFF, ADMIN or platform roles (unknown JSON fields such as
`role` are ignored; tested by `AuthIT.registrationCannotGrantPrivilegedRoles`).

### Permission matrix

Implemented as the `Permission` enum (`minimumRole` per permission) and tested row by row in
`PermissionTest`.

| Permission | CUSTOMER | STAFF | ADMIN | Used by |
|---|:-:|:-:|:-:|---|
| VIEW_QUEUE_BOARD | – | ✓ | ✓ | staff board, other customers' tickets |
| OPERATE_QUEUE | – | ✓ | ✓ | call-next, start, complete, no-show, open/pause/close |
| REGISTER_GUEST_WALK_IN | – | ✓ | ✓ | desk walk-ins |
| CHANGE_TICKET_PRIORITY | – | ✓ | ✓ | priority changes |
| MANAGE_CUSTOMER_BOOKINGS | – | ✓ | ✓ | check in / cancel on behalf of customers |
| VIEW_ORGANIZATION_MANAGEMENT | – | ✓ | ✓ | management overview, queue list |
| CONFIGURE_QUEUES | – | – | ✓ | create/edit queues |
| MANAGE_MEMBERS | – | – | ✓ | add/change/remove members |
| UPDATE_ORGANIZATION | – | – | ✓ | organization profile |

Customer self-service is authorized by **ownership** instead: the holder of a ticket or
appointment may view, cancel and check it in; nobody else (except staff of its organization).

### Tenant isolation

The organization is never taken from the client. For every protected operation the service:

1. resolves the organization **from the resource** (queue → organization, ticket → queue →
   organization, appointment → queue → organization), using a small projection query;
2. asks `AccessControlService.require(user, organization, permission)`, which reads the caller's
   membership **in that organization**;
3. only then locks and modifies.

So an admin of organization B who sends organization A's queue id, or puts A's id in a URL, is
refused. Membership ids are additionally checked to belong to the organization in the URL.

**403 vs 404.** When a resource is public (queues and organizations), lacking a role returns
`403`. When it is private (someone else's ticket or appointment), outsiders get `404`, so ids
cannot be probed. Staff operations on a ticket return `403` only to the ticket's own holder.

`TenantIsolationIT` (8 tests) and the Playwright test "staff of another organization cannot open
the board" verify this across organizations.

### URL rules (coarse layer)

`SecurityConfig` permits the public discovery GETs, requires a session for the rest of `/api/**`,
restricts `/api/platform/**` to `ROLE_PLATFORM_ADMIN`, exposes only `health` and `info` actuator
endpoints, and serves the SPA. Organization scoping is done in services because it cannot be
expressed as a URL pattern. The UI hides links by role only for convenience; **the backend checks
every request**.

## 4. Input validation and mass assignment

- Every write DTO is a Java record with Jakarta Bean Validation constraints (`@NotBlank`,
  `@Email`, `@Size`, `@Pattern`, `@Min/@Max`); cross-field rules (closing after opening, slot
  fits in opening hours, slot on the grid) are checked in services.
- DTOs contain only client-settable fields. Status, token number, priority (for customers),
  organization, role (outside admin endpoints), version and timestamps can never be set through a
  request. Entities are never bound directly from JSON.
- Database CHECK constraints repeat the critical validations.

## 5. Abuse protection

| Threat | Mitigation |
|---|---|
| Password guessing on one account | 5 failures per email in 15 min → that email is locked for 15 min (`429` + `Retry-After`) |
| Password spraying from one IP | 20 failures per IP in 15 min → IP locked for 15 min |
| Mass registration | 10 registrations per IP per hour |
| SSE connection exhaustion | 10 open streams per user |
| Slow or stuck locks | `lock_timeout` 5 s, transaction timeout 15 s |
| Slot hoarding | one active booking per customer, queue and day |

The limiter is in-memory and per instance (documented limitation; a shared store would be needed
with several instances). Client IPs come from `X-Forwarded-For` via Tomcat's `RemoteIpValve`,
which trusts only private-network proxy addresses, so clients cannot spoof their IP by sending the
header themselves.

## 6. Transport and headers (production profile)

- HTTPS terminates at Render's edge; requests that arrive over plain HTTP
  (`X-Forwarded-Proto: http`) are redirected to HTTPS by Spring Security; the internal health
  check is not redirected.
- `forward-headers-strategy: native` makes the app aware of HTTPS behind the proxy (HSTS is sent
  on HTTPS responses).
- Content-Security-Policy: `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`.
- `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy:
  strict-origin-when-cross-origin`, `Cache-Control: no-store` on API responses.
- **CORS:** no cross-origin access is configured at all. The SPA is served from the same origin in
  production, and the Vite dev server proxies `/api` in development, so the most restrictive
  setting (none) works.
- Database connections use TLS (`sslmode=require` in `DATABASE_URL`).

## 7. Secrets and privacy

- Secrets come only from environment variables (`DATABASE_*`, `QUEUELESS_SEED_PASSWORD`);
  `.env.example` contains placeholders; `.env*` files are git-ignored; the Docker build context
  excludes them; the frontend bundle contains no secrets.
- Demo data uses fictional names and `.example`/`.test` addresses. Production seeding refuses to
  run without an explicit password.
- SSE broadcasts carry ids only; public endpoints expose no customer data (tested:
  `RealtimeIT`, `QueueLifecycleIT.positionsAndEstimatesComeFromTheLiveQueue`, smoke test).
- Errors never include stack traces, SQL or internal messages; logs carry a request id instead.
- Actuator exposes only `health` (without details) and `info`.

## 8. Threats considered (STRIDE summary)

| Threat | Example | Control |
|---|---|---|
| Spoofing | stolen session | HTTP-only + Secure + SameSite cookie, session rotation, 8 h timeout, server-side logout |
| Tampering | editing ids in URLs or bodies to reach another tenant | resource-derived organization + membership check; DTO whitelists |
| Repudiation | "who cancelled my booking?" | `cancelled_by_user_id`, request ids in logs (a full audit log is future work) |
| Information disclosure | enumerating tickets/appointments, reading SSE | 404 for private resources, UUID ids, id-only events |
| Denial of service | lock hogging, login floods, many SSE streams | authorize before locking, lock/transaction timeouts, throttles, connection caps |
| Elevation of privilege | self-registering as admin, customer setting priority | roles only via admins; priority endpoint requires STAFF; permission matrix tests |

## 9. Known limitations

- No email verification or password reset (out of scope; would need an email provider).
- No multi-factor authentication.
- In-memory throttling and SSE registry are per instance.
- No audit-log table yet.
