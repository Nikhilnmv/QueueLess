# Queue algorithms

This page specifies how QueueLess numbers tokens, orders the line, computes positions and
estimates waiting times. Every rule here is implemented in the code paths named below and
exercised by the tests listed in [testing.md](testing.md).

## 1. Token allocation

**Rule.** Tokens are numbered 1, 2, 3, … per queue and per organization-local **service date**.
The displayed token is `prefix-NNN` (for example `G-007`; `ServiceQueue.displayToken`).

**Algorithm** (`TokenAllocator.nextToken`, always inside a transaction holding the queue lock):

1. `BEGIN`
2. Lock the queue row: `SELECT ... FROM service_queues WHERE id = :queue FOR UPDATE`
   (`QueueLockService.lock`; the method signature demands a `LockedQueue`, so calling the
   allocator without the lock does not compile).
3. `next = COALESCE(MAX(token_number), 0) + 1` for `(queue_id, service_date)`, answered from
   the `uq_tickets_token` index.
4. Insert the ticket (and its appointment when booking).
5. `COMMIT` — releases the lock.
6. After commit, publish the queue-change event.

**Why it is correct.** Only one transaction at a time can hold the queue lock, so step 3 can
never run concurrently for one queue. PostgreSQL runs at READ COMMITTED: each statement sees all
data committed before it began. The previous lock holder committed its ticket *before* releasing
the lock, so the next holder's `MAX` includes it. The unique constraint
`(queue_id, service_date, token_number)` is the final safeguard: even a future bug that forgot
the lock could not store a duplicate — the second insert would fail instead.

**Guarantees and limits.** Tokens are unique and gap-free per queue-day *for committed tickets*;
a rolled-back transaction does not consume a number. Cancelled tickets keep their number (tokens
are never reused). The lock serializes writes **per queue**; different queues proceed in
parallel. This is not a "globally scalable" design — see
[scalability-and-tradeoffs.md](scalability-and-tradeoffs.md) for the counter-table and
sharding alternatives.

Appointment tokens are reserved at **booking** time for the appointment's date. Walk-ins on that
day continue the same sequence, so token numbers reflect issue order, not service order.

## 2. Service order (priority policy)

Waiting tickets are served in this deterministic order
(`QueueTicketRepository.SERVICE_ORDER`, one definition used by every ordering query):

1. **Priority**: HIGH, then NORMAL, then LOW. Only staff can change priority
   (`PATCH /api/staff/tickets/{id}/priority`); customers always start NORMAL and cannot set it.
2. **Check-in time**, earliest first. Walk-ins check in when they join; appointment holders
   check in on arrival (from 60 minutes before their slot). Booking early does not jump the line
   for people already present; arriving early does.
3. **Creation time**, then **ticket id**: stable tie-breakers so the order is total and
   identical in every query.

The order is computed **in SQL** (`ORDER BY priority_rank, checked_in_at, created_at, id`)
and backed by the partial index `ix_tickets_waiting_order`. `priority_rank` is a stored
generated column (`HIGH=1, NORMAL=2, LOW=3`), so the index can serve the ordering.
Call-next takes the first row (`LIMIT 1`); positions and the staff board use the same query, so
"what you see" and "who is called" can never disagree. The policy text is returned in every
board response and shown to staff.

## 3. Queue position

Position is **derived, never stored**: a stored "position" column would be wrong as soon as
anyone ahead leaves, is called or is re-prioritized.

For a ticket in WAITING on today's service date:

```
waitingInOrder = SELECT ... WHERE queue = Q AND service_date = today AND status = 'WAITING' ORDER BY SERVICE_ORDER
position       = index of the ticket in waitingInOrder + 1        (1 = next to be called)
peopleAhead    = position - 1
```

A ticket at the counter (CALLED/SERVING) reports position 0. BOOKED tickets have no position
(they are not in line until check-in). Tickets of past service dates that never finished are
reported as `expired` and have no live data.

Reads run in a **read-only REPEATABLE READ transaction** (`QueueReadService`), so the waiting
list, the ticket at the counter and the service-time history all come from one database
snapshot: a call-next committing in the middle of a read cannot make a ticket appear both
waiting and called.

## 4. Waiting-time estimate

`WaitTimeEstimator` (pure Java, unit-tested) models one counter serving one ticket at a time.

**Average service time** (`serviceTimeStats`):

- Take the 20 most recently **completed** services of the queue
  (`completed_at - service_started_at`; index `ix_tickets_queue_completed`).
- Clamp each to **[1 minute, 3 × configured default]** so a ticket completed by mistake after a
  few seconds, or left open for hours, cannot distort the mean.
- If fewer than **3** samples exist, use the queue's configured `default_service_minutes`
  (`estimateBasis = DEFAULT`); otherwise the clamped mean (`estimateBasis = HISTORY`).

**Time until the counter is free** (`remainingAtCounter`):

| Ticket at the counter | Remaining |
|---|---|
| none | 0 |
| CALLED (service not started) | one full average service |
| SERVING since `t` | `max(0, average − (now − t))` |

**Estimate** for a ticket with `k` people ahead:

```
estimatedWait = remainingAtCounter + k × average        (rounded up to whole minutes)
```

The public "wait if you join now" uses `k = number currently waiting`.

**Worked example** (default 10 minutes, no history): G-001 is SERVING since 4 minutes ago, G-002
and G-003 are waiting, you are G-004.
remaining = 10 − 4 = 6 min; people ahead = 2; estimate = 6 + 2 × 10 = **26 minutes**.
(`QueueLifecycleIT.positionsAndEstimatesComeFromTheLiveQueue` checks this exact scenario.)

**Assumptions and limits** — shown to users as "estimate, not a guarantee":

- One counter per queue (the business rule enforced by `ux_tickets_active_per_queue_day`).
- Future arrivals with higher priority can still move ahead of you.
- A PAUSED queue keeps estimates but the UI states that they are on hold.
- No-shows and cancellations ahead of you make the real wait shorter than estimated.
- The mean adapts within ~20 services; it ignores time of day and staff differences.

## 5. "Turn approaching" notifications

After every change to a queue, inside the same transaction, `QueueChangeRecorder` looks at the
first **3** waiting tickets (configurable `queueless.notifications.approach-position`). Each
registered holder who has not been told yet receives one `TURN_APPROACHING` notification and
`approach_notified_at` is set. A partial unique index guarantees at most one such notification
per ticket even under concurrency. Being called creates `TURN_CALLED`; a no-show creates
`MARKED_NO_SHOW`.

## 6. Appointment slots

`SlotSchedule` builds the grid in minutes-of-day (so it can never wrap past midnight):
slots start at `opening_time` and repeat every `slot_duration_minutes`; a slot is offered only if
it **ends** by `closing_time`. Example: 09:00–17:00 with 15-minute slots gives 32 slots, the
last at 16:45.

`BookingRules.validate` accepts a requested instant only if, in the organization's time zone, it
is in the future, at most 14 days ahead (configurable), and exactly on the grid. Capacity is then
checked under the queue lock: bookings in the slot whose status is not CANCELLED count against
`capacity_per_slot`, so cancelling releases the place immediately.
