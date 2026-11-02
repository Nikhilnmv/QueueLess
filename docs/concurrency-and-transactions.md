# Concurrency and transactions

QueueLess has several "two people at the same moment" problems: two customers taking the last
place in a slot, two walk-ins getting the same token, two staff members pressing *Call next*
together, a customer cancelling while staff call them. This page explains how each is made safe
and how that is tested.

## 1. Strategy in one paragraph

Every write that touches a queue's tickets or appointments runs in **one transaction that
first locks that queue's row** (`SELECT ... FOR UPDATE`). Transactions for the same queue
therefore run one after another; transactions for different queues run in parallel. Inside the
lock, each operation re-reads the current state, validates it and writes. **Database
constraints** back up every invariant, so even a bug cannot store an impossible state.
Real-time events are published only **after commit**. Reads use **REPEATABLE READ** snapshots.

## 2. Transaction boundaries

| Operation | Service method | Locks | Writes in the same transaction |
|---|---|---|---|
| Book appointment | `AppointmentService.book` | queue row | appointment, ticket (token), CUSTOMER membership, notification, `state_version` |
| Cancel appointment | `AppointmentService.cancel` | queue row | appointment + ticket → CANCELLED, notification (if staff), version |
| Check in | `AppointmentService.checkIn` | queue row | ticket → WAITING, appointment → CHECKED_IN, approach notifications, version |
| Walk-in (customer) | `TicketService.joinQueue` | queue row | ticket (token), membership, approach notifications, version |
| Walk-in (guest, staff) | `TicketService.registerGuest` | queue row | ticket (token), version |
| Cancel ticket | `TicketService.cancel` | queue row | ticket (+ appointment) → CANCELLED, notification, version |
| Call next | `QueueOperationsService.callNext` | queue row | ticket → CALLED, TURN_CALLED + approach notifications, version |
| Start / complete / no-show / priority | `QueueOperationsService.*` | queue row | ticket (+ appointment), notifications, version |
| Open / pause / close, edit queue | `QueueService.changeStatus / update` | queue row | queue, version |
| Change / remove member | `MembershipService.*` | organization row | membership |
| Register | `AuthService.register` | none (unique email constraint decides) | user |
| Reads (board, ticket, live, my tickets) | `QueueReadService.*` | none; read-only REPEATABLE READ | — |

`spring.transaction.default-timeout=15s` bounds every transaction; the connection-level
`lock_timeout = 5s` bounds waiting for a lock. A lock wait that times out returns
`503 QUEUE_BUSY` with `Retry-After`, never a hung request.

## 3. The queue lock

```java
@Transactional(propagation = MANDATORY)          // must run inside the caller's transaction
public LockedQueue lock(UUID queueId) {
    ServiceQueue queue = entityManager.find(ServiceQueue.class, queueId);
    entityManager.refresh(queue, LockModeType.PESSIMISTIC_WRITE);  // SELECT ... FOR UPDATE + reload
    return new LockedQueue(queue);               // constructor is package-private
}
```

Design details worth knowing:

- **Proof of locking in the type system.** `TokenAllocator.nextToken(LockedQueue, ...)`,
  `TicketService.reserveForAppointment(LockedQueue, ...)` and similar methods only accept a
  `LockedQueue`, which only `QueueLockService.lock` can create. Forgetting the lock is a compile
  error, not a race found in production.
- **Refresh, not just lock.** A JPA locking *query* returns an entity that is already in the
  persistence context *unchanged*, i.e. possibly with state read before the lock was granted.
  `refresh(..., PESSIMISTIC_WRITE)` locks and re-reads, so decisions use current data.
- **Authorization before locking.** Staff operations first fetch only the queue's organization id
  (a scalar query) and check permissions, so unauthorized callers never queue up on the lock.
  Entities that will be modified are loaded *after* the lock (first load in the transaction), so
  they are current.
- **No deadlocks between business transactions.** Each transaction locks at most one queue row
  (membership changes lock one organization row and never a queue). With a single lock per
  transaction there is no lock-ordering cycle.
- **Java locks are not used.** `synchronized` would protect one JVM only; the database lock
  works across threads, processes and future application instances.

## 4. Race conditions and how they resolve

| Race | Without protection | With QueueLess |
|---|---|---|
| Two walk-ins at once | both read `MAX = 6`, both insert 7 | second waits for the lock, reads 7, inserts 8; `uq_tickets_token` rejects any duplicate |
| Two bookings for the last place in a slot | both count 1 < 2, both insert → 3 bookings in a 2-place slot | count happens under the lock; the second sees 2 and gets `409 SLOT_FULL` |
| Double-clicked "Confirm booking" (same Idempotency-Key) | two appointments | second request waits, finds the key, returns the first booking (`200`, `Idempotent-Replayed`); `uq_appointments_idempotency` backs it up |
| Same customer books two slots on one day at once | two bookings | under the lock the second sees the first (`ALREADY_BOOKED`); partial unique index backs it up |
| Two staff press Call next | the same ticket called twice, or two tickets at one counter | serialized; the second sees a ticket at the counter → `409 ACTIVE_TICKET_EXISTS`; partial unique index `ux_tickets_active_per_queue_day` backs it up |
| Customer cancels while staff call them | ticket both CALLED and CANCELLED, or appointment out of sync | one wins: either CALLED (cancel → `409`) or CANCELLED (call-next calls the next person); ticket and appointment change in the same transaction |
| Two cancellations of one appointment | double release of capacity, two events | first cancels; others see CANCELLED and return it unchanged (idempotent, one version bump, one event) |
| Two admins demote each other | organization without an admin | organization row lock serializes; the second sees one admin left → `409 LAST_ADMIN` |
| Same email registered twice at once | duplicate accounts | `uq_users_email` rejects the second insert → `409 EMAIL_ALREADY_REGISTERED` |
| Two first visits create the CUSTOMER membership | unique violation aborts a booking | `INSERT ... ON CONFLICT DO NOTHING` |

## 5. Invariants and where each is enforced

| Invariant | Service check (under lock) | Database guarantee |
|---|---|---|
| Unique token per queue and day | `MAX + 1` under the queue lock | `uq_tickets_token` |
| Slot never overbooked | count non-cancelled bookings under the lock | — (not expressible as a constraint; covered by concurrency tests) |
| ≤ 1 ticket CALLED/SERVING per queue and day | check before calling | partial unique index |
| ≤ 1 active ticket per customer, queue and day | check before inserting | partial unique index |
| ≤ 1 active booking per customer, queue and day | check before inserting | partial unique index |
| Appointment ↔ exactly one ticket, same queue | created together | unique + composite FK |
| Legal state transitions only | `TicketStatus.canTransitionTo`, `QueueTicket` methods | CHECK constraints tie timestamps to status |
| Appointment status mirrors its ticket | `AppointmentTicketSync` in the same transaction | — |
| Events only for committed changes | `@TransactionalEventListener(AFTER_COMMIT)` | — |

## 6. Isolation levels

- **Writes:** PostgreSQL default READ COMMITTED + explicit row locks. Under the queue lock the
  data each statement sees is exactly what previous lock holders committed — simpler and cheaper
  than SERIALIZABLE (which would add retry loops) and sufficient because all conflicting writers
  share one lock.
- **Reads:** `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` so the several queries
  behind one response (waiting list, counter, history) see one consistent snapshot. Read-only
  REPEATABLE READ transactions in PostgreSQL never fail with serialization errors.

## 7. Idempotency

- **Bookings:** client sends `Idempotency-Key` (a UUID generated when a slot is selected and
  reused on retries). The server stores it on the appointment (`UNIQUE (user_id, key)`). A replay
  returns the original booking with `200`; reusing a key for a different slot is `422`.
- **Cancellation, check-in, mark read:** naturally idempotent — repeating returns the same final
  state.
- **Walk-in:** the one-active-ticket rule makes a duplicate return `409 ALREADY_IN_QUEUE` with
  the existing `ticketId`, which the UI links to.
- **UI:** buttons are disabled while their request is pending.

## 8. Events after commit

Services publish `QueueChangedEvent` / `UserNotificationEvent` as Spring application events
inside the transaction. `RealtimeEventRelay` listens with
`@TransactionalEventListener(phase = AFTER_COMMIT)`: if the transaction rolls back, nothing is
sent. Notifications are rows written in the same transaction, so a notification exists if and
only if the change that caused it committed.

## 9. How this is tested (executed)

`ConcurrencyIT` runs against real PostgreSQL. Each scenario parks all threads on a
`CountDownLatch` start gate and releases them at once, so requests genuinely overlap.

| Test | Scenario | Asserted result |
|---|---|---|
| `simultaneousWalkInsReceiveUniqueConsecutiveTokens` | 30 customers join one queue at once | tokens are exactly 1..30; 30 events; `state_version` = 30 |
| `simultaneousBookingsNeverExceedSlotCapacity` | 15 customers book a 3-place slot | exactly 3 succeed, 12 get `SLOT_FULL`; tokens 1..3 |
| `theLastPlaceInASlotGoesToExactlyOneCustomer` | 10 customers, 1 place | exactly 1 succeeds |
| `simultaneousCallNextNeverAssignsATicketTwice` | 10 rounds × 8 concurrent call-next by 4 staff | exactly 1 success per round, never 2 tickets at the counter, each ticket called once in join order, 10 TICKET_CALLED events (failed attempts published nothing) |
| `duplicateBookingSubmissionsResolveToOneAppointment` | 10 identical submissions, same key | 1 created + 9 replays, same id, 1 row |
| `simultaneousBookingsByOneCustomerOnOneDayCreateOneAppointment` | 6 different slots, same day, no key | 1 success, 5 `ALREADY_BOOKED` |
| `simultaneousCancellationsAreIdempotentAndReleaseCapacityOnce` | 8 concurrent cancels | all return CANCELLED, one version bump, one event, place free again |
| `callNextRacingWithCancellationLeavesAConsistentState` | call-next vs cancel, 10 repetitions | always exactly one winner with matching state |
| `simultaneousRegistrationsWithOneEmailCreateOneAccount` | 6 HTTP registrations, one email | one 201, five 409, one row |
| `aRolledBackTransactionPublishesNothing` | change then forced rollback | no event, no ticket, no notification |

All 10 pass (see [testing.md](testing.md) for the run). These tests prove correctness under
contention; they are **not** throughput benchmarks, and no throughput figures are claimed.

## 10. Limitations

- One busy queue is a serialization point: its writes are applied one at a time. Each holds the
  lock for a few milliseconds, which is far beyond the expected demand of a physical counter,
  but it is a limit by design.
- `lock_timeout` 5 s: under extreme contention requests fail fast with `503` rather than wait.
- The login throttle and SSE registry are in-memory (single instance); see ADR 0005.
