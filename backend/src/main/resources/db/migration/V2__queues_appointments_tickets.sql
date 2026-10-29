-- QueueLess schema, part 2: service queues, appointments and queue tickets.

CREATE TABLE service_queues (
    id                      uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         uuid         NOT NULL REFERENCES organizations (id),
    name                    varchar(100) NOT NULL,
    description             varchar(500),
    token_prefix            varchar(5)   NOT NULL,
    status                  varchar(16)  NOT NULL,
    booking_enabled         boolean      NOT NULL DEFAULT true,
    opening_time            time         NOT NULL,
    closing_time            time         NOT NULL,
    slot_duration_minutes   integer      NOT NULL,
    capacity_per_slot       integer      NOT NULL,
    default_service_minutes integer      NOT NULL,
    -- Incremented (under the queue row lock) on every live-queue change. Sent in
    -- SSE events and snapshots so clients can tell which state is newer.
    state_version           bigint       NOT NULL DEFAULT 0,
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    -- Also serves "list queues of an organization" (leading column).
    CONSTRAINT uq_queues_org_name UNIQUE (organization_id, name),
    CONSTRAINT ck_queues_status CHECK (status IN ('OPEN', 'PAUSED', 'CLOSED')),
    CONSTRAINT ck_queues_hours CHECK (closing_time > opening_time),
    CONSTRAINT ck_queues_slot_duration CHECK (slot_duration_minutes BETWEEN 5 AND 240),
    CONSTRAINT ck_queues_capacity CHECK (capacity_per_slot BETWEEN 1 AND 100),
    CONSTRAINT ck_queues_service_minutes CHECK (default_service_minutes BETWEEN 1 AND 240),
    CONSTRAINT ck_queues_token_prefix CHECK (token_prefix ~ '^[A-Z]{1,5}$')
);

CREATE TABLE appointments (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    queue_id             uuid        NOT NULL REFERENCES service_queues (id),
    user_id              uuid        NOT NULL REFERENCES users (id),
    scheduled_at         timestamptz NOT NULL,
    -- Organization-local date of scheduled_at; denormalised so the
    -- one-booking-per-day rule can be a plain unique index.
    service_date         date        NOT NULL,
    booking_reference    varchar(16) NOT NULL,
    status               varchar(16) NOT NULL,
    idempotency_key      uuid,
    cancelled_at         timestamptz,
    cancelled_by_user_id uuid REFERENCES users (id),
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT uq_appointments_reference UNIQUE (booking_reference),
    -- Duplicate submissions with the same Idempotency-Key resolve to one booking.
    CONSTRAINT uq_appointments_idempotency UNIQUE (user_id, idempotency_key),
    -- Target of the composite foreign key from queue_tickets (same-queue guarantee).
    CONSTRAINT uq_appointments_id_queue UNIQUE (id, queue_id),
    CONSTRAINT ck_appointments_status
        CHECK (status IN ('BOOKED', 'CHECKED_IN', 'COMPLETED', 'NO_SHOW', 'CANCELLED')),
    CONSTRAINT ck_appointments_cancelled_at CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL)
);

-- Slot capacity checks and availability listing: appointments of a queue by time.
-- Cancelled bookings do not consume capacity, so they are excluded from the index.
CREATE INDEX ix_appointments_queue_scheduled
    ON appointments (queue_id, scheduled_at)
    WHERE status <> 'CANCELLED';

-- "My appointments" filtered by status and ordered by time.
CREATE INDEX ix_appointments_user_status ON appointments (user_id, status, scheduled_at);

-- Business rule: a customer holds at most one active booking per queue per day.
CREATE UNIQUE INDEX ux_appointments_active_per_user_day
    ON appointments (queue_id, user_id, service_date)
    WHERE status IN ('BOOKED', 'CHECKED_IN');

CREATE TABLE queue_tickets (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    queue_id             uuid         NOT NULL REFERENCES service_queues (id),
    -- NULL for guest walk-ins registered by staff (guest_name is then required).
    user_id              uuid         REFERENCES users (id),
    guest_name           varchar(120),
    appointment_id       uuid,
    source               varchar(16)  NOT NULL,
    service_date         date         NOT NULL,
    token_number         integer      NOT NULL,
    status               varchar(16)  NOT NULL,
    priority             varchar(8)   NOT NULL DEFAULT 'NORMAL',
    -- Numeric form of priority used for index-supported service ordering.
    priority_rank        smallint GENERATED ALWAYS AS (
                             CASE priority WHEN 'HIGH' THEN 1 WHEN 'NORMAL' THEN 2 ELSE 3 END
                         ) STORED,
    created_at           timestamptz  NOT NULL,
    checked_in_at        timestamptz,
    called_at            timestamptz,
    service_started_at   timestamptz,
    completed_at         timestamptz,
    no_show_at           timestamptz,
    cancelled_at         timestamptz,
    approach_notified_at timestamptz,
    updated_at           timestamptz  NOT NULL,
    -- Final safeguard for token allocation: one token number per queue per day.
    CONSTRAINT uq_tickets_token UNIQUE (queue_id, service_date, token_number),
    -- An appointment can never produce more than one ticket.
    CONSTRAINT uq_tickets_appointment UNIQUE (appointment_id),
    -- The appointment must belong to the same queue as the ticket.
    CONSTRAINT fk_tickets_appointment_same_queue
        FOREIGN KEY (appointment_id, queue_id) REFERENCES appointments (id, queue_id),
    CONSTRAINT ck_tickets_status
        CHECK (status IN ('BOOKED', 'WAITING', 'CALLED', 'SERVING', 'COMPLETED', 'NO_SHOW', 'CANCELLED')),
    CONSTRAINT ck_tickets_priority CHECK (priority IN ('HIGH', 'NORMAL', 'LOW')),
    CONSTRAINT ck_tickets_source CHECK (source IN ('APPOINTMENT', 'WALK_IN', 'GUEST_WALK_IN')),
    CONSTRAINT ck_tickets_source_appointment CHECK ((source = 'APPOINTMENT') = (appointment_id IS NOT NULL)),
    CONSTRAINT ck_tickets_holder CHECK (user_id IS NOT NULL OR guest_name IS NOT NULL),
    CONSTRAINT ck_tickets_token_positive CHECK (token_number > 0),
    -- Lifecycle timestamps must agree with the status (state machine invariants).
    CONSTRAINT ck_tickets_booked_not_checked_in CHECK (status <> 'BOOKED' OR checked_in_at IS NULL),
    CONSTRAINT ck_tickets_in_line_checked_in
        CHECK (status NOT IN ('WAITING', 'CALLED', 'SERVING', 'COMPLETED', 'NO_SHOW') OR checked_in_at IS NOT NULL),
    CONSTRAINT ck_tickets_called_at
        CHECK (status NOT IN ('CALLED', 'SERVING', 'COMPLETED', 'NO_SHOW') OR called_at IS NOT NULL),
    CONSTRAINT ck_tickets_service_started_at
        CHECK (status NOT IN ('SERVING', 'COMPLETED') OR service_started_at IS NOT NULL),
    CONSTRAINT ck_tickets_completed_at CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL),
    CONSTRAINT ck_tickets_no_show_at CHECK (status <> 'NO_SHOW' OR no_show_at IS NOT NULL),
    CONSTRAINT ck_tickets_cancelled_at CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL)
);

-- Business rule: one queue is one service counter, so at most one ticket per
-- queue and day can be in the CALLED/SERVING position.
CREATE UNIQUE INDEX ux_tickets_active_per_queue_day
    ON queue_tickets (queue_id, service_date)
    WHERE status IN ('CALLED', 'SERVING');

-- Call-next and queue positions: waiting tickets in service order
-- (priority, check-in time, creation time, id).
CREATE INDEX ix_tickets_waiting_order
    ON queue_tickets (queue_id, service_date, priority_rank, checked_in_at, created_at, id)
    WHERE status = 'WAITING';

-- Business rule: a customer holds at most one active ticket per queue per day.
CREATE UNIQUE INDEX ux_tickets_active_per_user_day
    ON queue_tickets (queue_id, user_id, service_date)
    WHERE user_id IS NOT NULL AND status IN ('BOOKED', 'WAITING', 'CALLED', 'SERVING');

-- "My tickets", newest service date first.
CREATE INDEX ix_tickets_user_date
    ON queue_tickets (user_id, service_date DESC)
    WHERE user_id IS NOT NULL;

-- Average service time: the most recently completed tickets of a queue.
CREATE INDEX ix_tickets_queue_completed
    ON queue_tickets (queue_id, completed_at DESC)
    WHERE status = 'COMPLETED';

-- Note: uq_tickets_token (queue_id, service_date, token_number) also serves the
-- staff board ("all tickets of a queue for a day") and MAX(token_number) lookups
-- through its leading columns, so no separate (queue_id, service_date) index exists.
