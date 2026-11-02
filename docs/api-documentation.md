# API documentation

All endpoints are under `/api`, return JSON and are described live by OpenAPI at
`/v3/api-docs` with Swagger UI at `/swagger-ui.html`. This page explains how to use them,
who may call each one, and what happens in the database.

## Conventions

**Versioning.** This is version 1 of the API; it is served unprefixed under `/api`. A breaking
change would be introduced side by side under `/api/v2/...` while v1 keeps working.

**Authentication.** Session cookie. `POST /api/auth/login` (or `/register`) sets the HTTP-only
cookie `QUEUELESS_SESSION`; the browser sends it automatically. Sessions are stored in
PostgreSQL and expire after 8 hours of inactivity. There are no tokens in localStorage.

**CSRF.** Every state-changing request (POST/PUT/PATCH/DELETE, including login) must send the
header `X-XSRF-TOKEN` with the value of the `XSRF-TOKEN` cookie, which the server issues on any
response (call `GET /api/auth/me` first if you have none). Missing or wrong token → `403
CSRF_REJECTED`. GET requests (including SSE) need no token.

**Errors.** Every error is an RFC 9457 problem detail with a machine-readable `code`:

```json
{
  "type": "urn:queueless:problem:slot-full",
  "title": "Appointment slot is full",
  "status": 409,
  "detail": "This slot is fully booked. Please choose another time.",
  "instance": "/api/queues/0b5c.../appointments",
  "code": "SLOT_FULL"
}
```

Validation errors add `"errors": [{"field": "email", "message": "must be a well-formed email address"}]`.
Some conflicts add context, e.g. `ALREADY_IN_QUEUE` includes `ticketId`, `ACTIVE_TICKET_EXISTS`
includes the ticket at the counter. 5xx responses include a `requestId` that matches the server
log (every response also carries an `X-Request-Id` header). Stack traces, SQL and internal
details are never returned.

**Status codes used.**

| Status | Meaning in QueueLess |
|---|---|
| 200 / 201 / 204 | success / created (with `Location`) / no body |
| 400 | invalid input: `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_SLOT` |
| 401 | not logged in (`AUTHENTICATION_REQUIRED`) or wrong credentials (`INVALID_CREDENTIALS`) |
| 403 | logged in but lacking the role **in this organization** (`ACCESS_DENIED`), or CSRF failure |
| 404 | resource missing, organization inactive, **or a private resource of someone else** (existence is not revealed) |
| 409 | business conflict: capacity, illegal state transition, duplicates, last admin |
| 422 | `IDEMPOTENCY_KEY_REUSED` (same key, different booking) |
| 429 | throttled login/registration or too many open live connections (`Retry-After` header) |
| 503 | `QUEUE_BUSY`: the queue lock could not be obtained within 5 s; retry |

**Roles.** "Customer" means any logged-in user. "STAFF" and "ADMIN" always mean *a membership
with that role in the organization that owns the queue/ticket/appointment being touched* — never
a global role. ADMIN includes every STAFF permission. See [security.md](security.md).

**Transactions.** Every write runs in one database transaction. Writes that touch a queue's
tickets or appointments first take that queue's row lock (`SELECT ... FOR UPDATE`), so they are
applied one at a time per queue. Successful queue changes increment `service_queues.state_version`
and, **after commit**, push a `queue-changed` SSE event.

## Authentication

| Method & path | Who | Request | Responses | Database |
|---|---|---|---|---|
| `POST /api/auth/register` | public | `{email, fullName (2–120), password (8–72)}` | `201` current user + session; `400`; `409 EMAIL_ALREADY_REGISTERED`; `429` (10/hour/IP) | insert `users` (bcrypt hash); insert session |
| `POST /api/auth/login` | public | `{email, password}` | `200` current user + new session id + new CSRF token; `401 INVALID_CREDENTIALS` (same message for unknown email and wrong password); `429` after 5 failures per email or 20 per IP in 15 min (locked 15 min) | insert session |
| `POST /api/auth/logout` | any | — | `204`, session deleted, cookie expired | delete session |
| `GET /api/auth/me` | logged in | — | `200 {id, email, fullName, platformAdmin, memberships:[{organizationId, organizationName, organizationSlug, role}]}`; `401` | — |

Public registration always creates a plain customer; extra fields such as `role` or
`platformAdmin` are ignored.

## Discovery (public)

| Method & path | Response |
|---|---|
| `GET /api/organizations?search=text` | active organizations `[{id, name, slug, description, timezone}]` |
| `GET /api/organizations/{slug}` | one organization; `404` if missing or inactive |
| `GET /api/organizations/{slug}/queues` | queues with configuration and status |
| `GET /api/queues/{queueId}` | `{id, organizationId, organizationName, organizationSlug, timezone, name, description, tokenPrefix, status, bookingEnabled, openingTime, closingTime, slotDurationMinutes, capacityPerSlot, defaultServiceMinutes, stateVersion}` |
| `GET /api/queues/{queueId}/live` | `{status, serviceDate, nowServing {displayToken, status}, waitingCount, estimatedWaitMinutesForNewTicket, averageServiceMinutes, estimateBasis (HISTORY or DEFAULT), estimateSamples, version}` — no customer data |
| `GET /api/queues/{queueId}/slots?date=YYYY-MM-DD` | `{date, timezone, bookingEnabled, slotDurationMinutes, firstBookableDate, lastBookableDate, slots:[{startsAt, localTime, capacity, booked, available, bookable}]}`; date defaults to today (organization zone); `400 INVALID_SLOT` outside today…today+14 |

## Appointments

| Method & path | Who | Request | Responses | Database |
|---|---|---|---|---|
| `POST /api/queues/{queueId}/appointments` | customer | `{scheduledAt: ISO instant}`, optional header `Idempotency-Key: <uuid>` | `201` appointment; `200` + `Idempotent-Replayed: true` when the key was already used for the same booking; `400 INVALID_SLOT` (past, beyond 14 days, off the slot grid); `409 SLOT_FULL`, `ALREADY_BOOKED` (one active booking per queue per day), `ALREADY_IN_QUEUE`, `BOOKING_DISABLED`; `422 IDEMPOTENCY_KEY_REUSED`; `503` | under the queue lock: count, insert `appointments` (BOOKED, reference), insert `queue_tickets` (BOOKED, next token), insert CUSTOMER membership if absent, insert notification `APPOINTMENT_CONFIRMED`, bump version |
| `GET /api/me/appointments` | customer | — | `200` newest 50 | — |
| `GET /api/appointments/{id}` | owner, or STAFF of its organization | — | `200 {id, bookingReference, status, queue..., scheduledAt, serviceDate, ticketId, tokenNumber, displayToken, ticketStatus, checkInOpensAt, canCancel, canCheckIn}`; `404` for everyone else | — |
| `POST /api/appointments/{id}/cancel` | owner or STAFF | — | `200` (repeating it returns the same cancelled appointment); `409 INVALID_STATE_TRANSITION` if completed/no-show or the ticket is already at the counter; `404` | under the lock: appointment and ticket → CANCELLED, capacity released, notification to the customer if staff cancelled, bump version |
| `POST /api/appointments/{id}/check-in` | owner or STAFF | — | `200` ticket (now WAITING, with live position); repeat is harmless; `409 CHECK_IN_NOT_ALLOWED` (not the appointment day, or more than 60 min early), `QUEUE_NOT_ACCEPTING` (queue closed), `INVALID_STATE_TRANSITION` | under the lock: ticket BOOKED→WAITING with `checked_in_at`, appointment → CHECKED_IN, approach notifications, bump version |

`canCancel` and `canCheckIn` are computed by the server so the UI never decides permissions itself.

## Tickets (customer side)

| Method & path | Who | Responses | Database |
|---|---|---|---|
| `POST /api/queues/{queueId}/tickets` | customer | `201` ticket with token and live position; `409 ALREADY_IN_QUEUE` (+`ticketId`), `QUEUE_NOT_ACCEPTING` (queue CLOSED); `503` | under the lock: insert WAITING ticket with `MAX+1` token, CUSTOMER membership if absent, approach notifications, bump version |
| `GET /api/me/tickets` | customer | `200` 30 most recent tickets; active tickets of today include `live {position, peopleAhead, estimatedWaitMinutes, nowServing, waitingCount, queueStatus, averageServiceMinutes, estimateBasis}`; stale ones have `expired: true` | — |
| `GET /api/tickets/{ticketId}` | holder, or STAFF of its organization | `200`; `404` otherwise | read-only REPEATABLE READ snapshot |
| `POST /api/tickets/{ticketId}/cancel` | holder or STAFF | `200` (idempotent); `409 INVALID_STATE_TRANSITION` once CALLED/SERVING/finished; `404` | under the lock: → CANCELLED (+ appointment), positions close up, notification if staff cancelled |

## Staff operations (STAFF or ADMIN of the queue's organization)

| Method & path | Request | Responses | Database |
|---|---|---|---|
| `GET /api/staff/queues/{queueId}/board` | — | `200 {status, serviceDate, version, atCounter, waiting:[{position, estimatedWaitMinutes, displayToken, holderName, source, priority, checkedInAt...}], expected:[booked appointments not yet checked in], stats {waiting, expected, completed, noShow, cancelled, averageServiceMinutes, estimateBasis}, orderingPolicy}` | read-only REPEATABLE READ |
| `POST /api/staff/queues/{queueId}/call-next` | — | `200` the called ticket; `409 QUEUE_NOT_OPEN`, `ACTIVE_TICKET_EXISTS`, `NO_WAITING_TICKETS`; `403`; `503` | under the lock: first WAITING in service order → CALLED, `TURN_CALLED` notification, approach notifications, bump version |
| `POST /api/staff/queues/{queueId}/walk-ins` | `{guestName (1–120), priority?: HIGH/NORMAL/LOW}` | `201` ticket; `409 QUEUE_NOT_ACCEPTING` | under the lock: guest ticket (no account) with next token |
| `POST /api/staff/queues/{queueId}/status` | `{status: OPEN/PAUSED/CLOSED}` | `200` queue | under the lock: status, bump version |
| `POST /api/staff/tickets/{ticketId}/start` | — | `200`; `409` unless CALLED | CALLED → SERVING |
| `POST /api/staff/tickets/{ticketId}/complete` | — | `200`; `409` unless SERVING | SERVING → COMPLETED (+ appointment COMPLETED); duration feeds estimates |
| `POST /api/staff/tickets/{ticketId}/no-show` | — | `200`; `409` unless CALLED | CALLED → NO_SHOW (+ appointment), `MARKED_NO_SHOW` notification |
| `PATCH /api/staff/tickets/{ticketId}/priority` | `{priority}` | `200`; `409` unless BOOKED/WAITING | priority changed; order recomputed |

For ticket endpoints, a caller who is not staff of the ticket's organization receives `404`
(or `403` if it is their own ticket), so other organizations cannot even confirm a ticket id.

## Organization administration

| Method & path | Who | Request | Responses |
|---|---|---|---|
| `GET /api/admin/organizations/{orgId}` | STAFF/ADMIN | — | `200 {organization, yourRole, members {admins, staff, customers}}` |
| `PATCH /api/admin/organizations/{orgId}` | ADMIN | `{name, description}` (slug and time zone are fixed) | `200` |
| `GET /api/admin/organizations/{orgId}/queues` | STAFF/ADMIN | — | `200` queues |
| `POST /api/admin/organizations/{orgId}/queues` | ADMIN | `{name, description, tokenPrefix (1–5 letters), openingTime "09:00", closingTime, slotDurationMinutes (5–240), capacityPerSlot (1–100), defaultServiceMinutes (1–240), bookingEnabled}` | `201` (new queues start CLOSED); `400` (e.g. closing ≤ opening, no slot fits); `409 DUPLICATE_NAME` |
| `PUT /api/admin/queues/{queueId}` | ADMIN | same body | `200`; locks the queue so capacity changes are serialized with bookings; existing bookings keep their times |
| `GET /api/admin/organizations/{orgId}/members` | ADMIN | — | `200 [{membershipId, userId, email, fullName, role, memberSince}]` |
| `POST /api/admin/organizations/{orgId}/members` | ADMIN | `{email, role}` (user must be registered) | `201`; `404` no such user; `409 MEMBERSHIP_EXISTS` (+`membershipId`) |
| `PATCH /api/admin/organizations/{orgId}/members/{membershipId}` | ADMIN | `{role}` | `200`; `409 LAST_ADMIN`; `404` if the membership belongs to another organization |
| `DELETE /api/admin/organizations/{orgId}/members/{membershipId}` | ADMIN | — | `204`; `409 LAST_ADMIN` |

Membership changes lock the organization row so two admins cannot demote each other at once.

## Platform

| Method & path | Who | Request | Responses |
|---|---|---|---|
| `POST /api/platform/organizations` | platform admin (`ROLE_PLATFORM_ADMIN`) | `{name, slug, description, timezone, adminEmail}` | `201`; `404` admin email not registered; `409` slug taken; `400` unknown time zone |

## Notifications

| Method & path | Who | Responses |
|---|---|---|
| `GET /api/me/notifications?unreadOnly=false&limit=50` | customer | `200 {items:[{id, type, title, message, ticketId, createdAt, readAt}], unreadCount}` (limit 1–100) |
| `POST /api/me/notifications/{id}/read` | owner | `200` (idempotent); `404` for other users' notifications |
| `POST /api/me/notifications/read-all` | owner | `200 {updated, unreadCount}` |

## Real time (Server-Sent Events)

| Stream | Who | Events |
|---|---|---|
| `GET /api/queues/{queueId}/events` | any logged-in user; queue must exist in an active organization | `ready {queueId, version}` on every (re)connect; `queue-changed {queueId, version, reason, occurredAt}` after each committed change; comment `:heartbeat` every 20 s |
| `GET /api/me/events` | logged-in user | `ready {userId, unreadCount}`; `notification {notificationId, type, ticketId}` |

Events never contain names, emails or other customer data. Clients treat them as "something
changed" and re-fetch what they are authorized to see. `retry: 3000` suggests a 3-second
reconnect. At most 10 open streams per user (`429` beyond). Details:
[realtime-design.md](realtime-design.md).

```bash
curl -N -b cookies.txt -H 'Accept: text/event-stream' https://HOST/api/queues/QUEUE_ID/events
```

## Health and documentation

| Path | Purpose |
|---|---|
| `GET /actuator/health` | overall status with components (`db`, `diskSpace`, ...); details hidden |
| `GET /actuator/health/liveness` | process alive; **never touches the database** (hosting health check) |
| `GET /actuator/health/readiness` | ready to serve, **including the database** |
| `/swagger-ui.html`, `/v3/api-docs` | interactive documentation (disable with `QUEUELESS_API_DOCS_ENABLED=false`) |

## Walkthrough with curl

```bash
BASE=http://localhost:8080
# 1. Obtain the CSRF cookie, then log in (dev demo account).
curl -s -c jar -b jar $BASE/api/auth/me > /dev/null
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' jar)
curl -s -c jar -b jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
     -d '{"email":"asha@example.com","password":"Password123!"}' $BASE/api/auth/login

# 2. Find a queue and its free slots for tomorrow.
QUEUE=$(curl -s $BASE/api/organizations/city-care-clinic/queues | python3 -c \
  "import sys,json; print(json.load(sys.stdin)[0]['id'])")
curl -s "$BASE/api/queues/$QUEUE/slots?date=2026-11-03"

# 3. Book (safe to retry with the same key).
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' jar)
curl -s -c jar -b jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
     -H "Idempotency-Key: $(uuidgen)" -d '{"scheduledAt":"2026-11-03T04:30:00Z"}' \
     $BASE/api/queues/$QUEUE/appointments
```

`scripts/smoke-test.sh` exercises every major endpoint this way and is the executable form of
this page.
